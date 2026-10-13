package app.gamenative.utils

import android.content.res.AssetManager
import com.winlator.core.TarCompressorUtils
import timber.log.Timber
import java.io.File

object AssetUtils {
    fun log(): Timber.Tree {
        return Timber.tag("AssetUtils")
    }

    fun extractComponentsWithVersionCheck(
        extractionPairs: List<Pair<String, File>>,
        assetManager: AssetManager,
        extractType: TarCompressorUtils.Type
    ) {
        for ((assetFile, targetDir) in extractionPairs) {
            if (targetDir.exists() && (targetDir.list()?.isNotEmpty() == true)) continue
            log().i("Extracting $assetFile to ${targetDir.absolutePath}")
            val tempDir = File(targetDir.parentFile, "${targetDir.name}.tmp")
            if (tempDir.exists()) tempDir.deleteRecursively()
            tempDir.mkdirs()

            val success = TarCompressorUtils.extract(
                extractType,
                assetManager,
                assetFile,
                tempDir
            )

            if (success) {
                if (targetDir.exists()) targetDir.deleteRecursively()
                if (!tempDir.renameTo(targetDir)) {
                    log().e("Failed to promote extracted dir for $assetFile")
                    tempDir.deleteRecursively()
                    continue
                }
                log().i("Successfully extracted $assetFile")
            } else {
                tempDir.deleteRecursively()
                log().e("Failed to extract $assetFile")
            }
        }
    }
}
