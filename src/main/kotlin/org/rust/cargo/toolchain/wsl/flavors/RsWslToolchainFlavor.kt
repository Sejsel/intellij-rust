/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.toolchain.wsl.flavors

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WSLUtil
import org.rust.cargo.toolchain.flavors.RsToolchainFlavor
import org.rust.cargo.toolchain.wsl.fetchInstalledWslDistributions
import org.rust.cargo.toolchain.wsl.hasExecutable
import org.rust.cargo.toolchain.wsl.pathToExecutable
import java.nio.file.Path

abstract class RsWslToolchainFlavor : RsToolchainFlavor() {

    override fun isApplicable(): Boolean =
        WSLUtil.isSystemCompatible() && fetchInstalledWslDistributions().isNotEmpty()

    // BACKCOMPAT: 2020.3
    // Replace with [WslDistributionManager.isWslPath]
    override fun isValidToolchainPath(path: Path): Boolean =
        path.toString().startsWith(WSLDistribution.UNC_PREFIX) && super.isValidToolchainPath(path)

    override fun hasExecutable(path: Path, toolName: String): Boolean = path.hasExecutable(toolName)

    override fun pathToExecutable(path: Path, toolName: String): Path = path.pathToExecutable(toolName)
}
