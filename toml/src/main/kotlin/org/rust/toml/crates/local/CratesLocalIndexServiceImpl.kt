/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.toml.crates.local

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.EnvironmentUtil
import com.intellij.util.io.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.OrTreeFilter
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.treewalk.filter.TreeFilter
import org.rust.openapiext.RsPathManager
import org.rust.stdext.cleanDirectory
import org.rust.stdext.supplyAsync
import org.rust.toml.crates.local.CratesLocalIndexServiceImpl.Companion.CratesLocalIndexState
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Collectors


/**
 * Crates local index, created from user cargo registry index on host machine.
 * Used for dependency code insight in project's `Cargo.toml`.
 *
 * Stores crates info in [crates] persistent hash map and hash for commit which has been used for index load in
 * persistent state [CratesLocalIndexState]. Note, state's properties should be mutable in order to be serialized and
 * saved.
 */
@State(name = "CratesLocalIndexState", storages = [
    Storage(StoragePathMacros.NON_ROAMABLE_FILE, roamingType = RoamingType.DISABLED)
])
class CratesLocalIndexServiceImpl
    : CratesLocalIndexService, PersistentStateComponent<CratesLocalIndexState>, Disposable {

    // TODO: handle RepositoryNotFoundException
    private val repository: Repository = FileRepositoryBuilder()
        .setGitDir(cargoRegistryIndexPath.toFile())
        .build()

    private val registryHeadCommitHash: String
        get() {
            // BACKCOMPAT: Rust 1.49
            // Since 1.50 there should always be CARGO_REGISTRY_INDEX_TAG
            val objectId = repository.resolve(CARGO_REGISTRY_INDEX_TAG)
                ?: repository.resolve(CARGO_REGISTRY_INDEX_TAG_PRE_1_50)

            return objectId?.name ?: run {
                LOG.error("Failed to resolve remote branch in the cargo registry index repository")
                INVALID_COMMIT_HASH
            }
        }

    private val crates: PersistentHashMap<String, CargoRegistryCrate>? = run {
        resetIndexIfNeeded()

        val file = baseCratesLocalRegistryDir.resolve("crates-local-index")
        try {
            IOUtil.openCleanOrResetBroken({
                PersistentHashMap(
                    file,
                    EnumeratorStringDescriptor.INSTANCE,
                    CrateExternalizer,
                    4 * 1024,
                    CRATES_INDEX_VERSION
                )
            }, file)
        } catch (e: IOException) {
            LOG.error("Cannot open or create PersistentHashMap in $file", e)
            null
        }
    }

    @Volatile
    private var state: CratesLocalIndexState = CratesLocalIndexState()

    /**
     * [isUpdating] will be true when index is performing [CratesLocalIndexUpdateTask]
     */
    private val isUpdating: AtomicBoolean = AtomicBoolean(false)

    init {
        val cargoRegistryIndexRefsLocation = Paths.get(cargoRegistryIndexPath.toString(), "refs/").toString()
        LocalFileSystem.getInstance().addRootToWatch(cargoRegistryIndexRefsLocation, true)

        // VFS fills up lazily, therefore we need to explicitly add root directory and go through children
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(cargoRegistryIndexRefsLocation)
        if (root != null) {
            VfsUtilCore.processFilesRecursively(root) { true }
            RefreshQueue.getInstance().refresh(true, true, null, root)
        } else {
            LOG.error("Failed to subscribe to cargo registry changes in $cargoRegistryIndexRefsLocation")
        }

        // Watch cargo registry repo and update crates local index on changes
        ApplicationManager.getApplication().messageBus.connect().subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.startsWith(cargoRegistryIndexRefsLocation) }) {
                    updateIfNeeded()
                }
            }
        })
    }

    override fun getState(): CratesLocalIndexState = state
    override fun loadState(state: CratesLocalIndexState) {
        this.state = state
    }

    override fun getCrate(crateName: String): CargoRegistryCrate? {
        if (isUpdating.get()) throw CratesLocalIndexException("Index is being updated")
        if (crates == null) throw CratesLocalIndexException("PersistentHashMap is not available")

        return try {
            crates.get(crateName)
        } catch (e: IOException) {
            LOG.error("Failed to get crate $crateName", e)
            null
        }
    }

    override fun getAllCrateNames(): List<String> {
        if (isUpdating.get()) throw CratesLocalIndexException("Index is being updated")
        if (crates == null) throw CratesLocalIndexException("PersistentHashMap is not available")

        val crateNames = mutableListOf<String>()

        try {
            crates.processKeys { name ->
                crateNames.add(name)
            }
        } catch (e: IOException) {
            LOG.error("Failed to get crate names", e)
        }

        return crateNames
    }

    override fun updateIfNeeded() {
        resetStateIfIndexEmpty()

        if (state.indexedCommitHash != registryHeadCommitHash && isUpdating.compareAndSet(false, true)) {
            CratesLocalIndexUpdateTask(registryHeadCommitHash).queue()
        }
    }

    private fun resetStateIfIndexEmpty() {
        var isEmpty = true

        try {
            crates?.processKeysWithExistingMapping {
                isEmpty = false
                false
            }
        } catch (e: IOException) {
            LOG.warn(e)
        }

        if (isEmpty) {
            state = CratesLocalIndexState(INVALID_COMMIT_HASH)
        }
    }

    private fun resetIndexIfNeeded() {
        if (corruptionMarkerFile.exists()) {
            baseCratesLocalRegistryDir.cleanDirectory()
        }
    }

    fun invalidateCaches() {
        corruptionMarkerFile.apply {
            parent?.createDirectories()
            Files.createFile(this)
            state = CratesLocalIndexState(INVALID_COMMIT_HASH)
        }
    }

    private fun updateCrates(currentHeadHash: String, prevHeadHash: String) {
        val reader = repository.newObjectReader()
        val currentTreeIter = CanonicalTreeParser().apply {
            val currentHeadTree = repository.resolve("$currentHeadHash^{tree}") ?: run {
                LOG.error("Git revision `$currentHeadHash^{tree}` cannot be resolved to any object id")
                return
            }
            reset(reader, currentHeadTree)
        }

        val filter = run {
            val prevHeadTree = repository.resolve("$prevHeadHash^{tree}") ?: return@run TreeFilter.ALL

            val prevTreeIter = CanonicalTreeParser().apply {
                reset(reader, prevHeadTree)
            }

            val git = Git(repository)

            val changes = try {
                git.diff()
                    .setNewTree(currentTreeIter)
                    .setOldTree(prevTreeIter)
                    .call()
            } catch (e: GitAPIException) {
                LOG.error("Failed to calculate diff due to Git API error: ${e.message}")
                return@run TreeFilter.ALL
            }

            when (changes.size) {
                0 -> TreeFilter.ALL
                1 -> PathFilter.create(changes.single().newPath)
                else -> OrTreeFilter.create(changes.map { PathFilter.create(it.newPath) })
            }
        }

        val revTree = RevWalk(repository).parseCommit(ObjectId.fromString(currentHeadHash)).tree
        val mapper = JsonMapper()
            .registerKotlinModule()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

        TreeWalk(repository).use { treeWalk ->
            treeWalk.addTree(revTree)
            treeWalk.filter = filter
            treeWalk.isRecursive = true
            treeWalk.isPostOrderTraversal = false

            val objectIds = mutableListOf<Pair<ObjectId, String>>()
            while (treeWalk.next()) {
                if (treeWalk.isSubtree) continue
                if (treeWalk.nameString == "config.json") continue

                val objectId = treeWalk.getObjectId(0)
                objectIds.add(objectId to treeWalk.nameString)
            }
            val pool = Executors.newWorkStealingPool(2)
            val future = supplyAsync(pool) {
                objectIds
                    .parallelStream()
                    .map { (objectId, name) ->
                        val loader = repository.open(objectId)
                        val versions = mutableListOf<CargoRegistryCrateVersion>()
                        val fileReader = BufferedReader(InputStreamReader(loader.openStream(), Charsets.UTF_8))

                        fileReader.forEachLine { line ->
                            if (line.isBlank()) return@forEachLine

                            try {
                                versions.add(crateFromJson(line, mapper))
                            } catch (e: Exception) {
                                LOG.warn("Failed to parse JSON from ${treeWalk.pathString}, line ${line}: ${e.message}")
                            }
                        }
                        name to CargoRegistryCrate(versions)
                    }
                    .collect(Collectors.toList())
            }
            val crateList = try {
                future.join()
            } finally {
                pool.shutdownNow()
            }

            crateList.forEach { (name, crate) ->
                try {
                    crates?.put(name, crate)
                } catch (e: IOException) {
                    LOG.error("Failed to put crate `$name` into local index", e)
                }
            }
        }

        // Force to save everything on the disk
        try {
            crates?.force()
        } catch (e: IOException) {
            LOG.warn(e)
        }
    }

    override fun dispose() {
        try {
            crates?.close()
        } catch (e: IOException) {
            LOG.warn(e)
        }
    }

    private inner class CratesLocalIndexUpdateTask(val newHead: String) : Task.Backgroundable(null, "Loading cargo registry index", false) {
        override fun run(indicator: ProgressIndicator) {
            updateCrates(newHead, state.indexedCommitHash)
        }

        override fun onSuccess() {
            state = CratesLocalIndexState(newHead)
        }

        override fun onFinished() {
            isUpdating.set(false)
        }
    }

    companion object {
        data class CratesLocalIndexState(var indexedCommitHash: String = "")

        private val corruptionMarkerFile: Path
            get() = baseCratesLocalRegistryDir.resolve(CORRUPTION_MARKER_NAME)

        private val baseCratesLocalRegistryDir: Path
            get() = RsPathManager.pluginDirInSystem().resolve("crates-local-index")

        private val cargoHome: String
            get() = EnvironmentUtil.getValue("CARGO_HOME")
                ?: Paths.get(System.getProperty("user.home"), ".cargo/").toString()

        // Currently for crates.io only
        private val cargoRegistryIndexPath: Path
            get() = Paths.get(cargoHome, "registry/index/", CRATES_IO_HASH, ".git/")

        // Crates.io index hash is permanent.
        // See https://github.com/rust-lang/cargo/issues/8572
        private const val CRATES_IO_HASH = "github.com-1ecc6299db9ec823"

        private const val CARGO_REGISTRY_INDEX_TAG_PRE_1_50: String = "origin/master"
        private const val CARGO_REGISTRY_INDEX_TAG: String = "origin/HEAD"
        private const val CORRUPTION_MARKER_NAME: String = "corruption.marker"
        private const val INVALID_COMMIT_HASH: String = "<invalid>"
        private const val CRATES_INDEX_VERSION: Int = 0

        private val LOG: Logger = logger<CratesLocalIndexServiceImpl>()
    }
}

private object CrateExternalizer : DataExternalizer<CargoRegistryCrate> {
    override fun save(out: DataOutput, value: CargoRegistryCrate) {
        out.writeInt(value.versions.size)
        value.versions.forEach { version ->
            out.writeUTF(version.version)
            out.writeBoolean(version.isYanked)

            out.writeInt(version.features.size)
            version.features.forEach { feature ->
                out.writeUTF(feature)
            }
        }
    }

    override fun read(inp: DataInput): CargoRegistryCrate {
        val versions = mutableListOf<CargoRegistryCrateVersion>()
        val versionsSize = inp.readInt()
        repeat(versionsSize) {
            val version = inp.readUTF()
            val yanked = inp.readBoolean()

            val features = mutableListOf<String>()
            val featuresSize = inp.readInt()
            repeat(featuresSize) {
                features.add(inp.readUTF())
            }
            versions.add(CargoRegistryCrateVersion(version, yanked, features))
        }
        return CargoRegistryCrate(versions)
    }
}

data class ParsedVersion(
    val name: String,
    val vers: String,
    val yanked: Boolean,
    val features: HashMap<String, List<String>>
)

private fun crateFromJson(json: String, mapper: ObjectMapper): CargoRegistryCrateVersion {
    val parsedVersion = mapper.readValue<ParsedVersion>(json)

    return CargoRegistryCrateVersion(
        parsedVersion.vers,
        parsedVersion.yanked,
        parsedVersion.features.map { it.key }
    )
}
