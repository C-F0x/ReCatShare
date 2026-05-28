package moe.reimu.catshare.utils

import android.os.Build
import moe.reimu.catshare.AppSettings
import moe.reimu.catshare.MyApplication
import java.util.Random

data class BrandConfig(
    val idRange: IntRange,
    val name: String,
    val searchKeys: List<String> = emptyList(),
    val isPrimary: Boolean = true
)

object DeviceUtils {
    // Single Source of Truth for Brand Mappings
    private val BRAND_REGISTRY = listOf(
        BrandConfig(114514..114514, "ReCatShare"),
        BrandConfig(10..19, "OPPO", listOf("oppo")),
        BrandConfig(11..11, "realme", listOf("realme")),
        BrandConfig(41..45, "OnePlus", listOf("oneplus")),
        BrandConfig(20..29, "vivo", listOf("vivo")),
        BrandConfig(30..30, "Xiaomi", listOf("xiaomi", "redmi")),
        BrandConfig(31..31, "BlackShark", listOf("blackshark", "black shark")),
        BrandConfig(50..59, "Meizu", listOf("meizu")),
        BrandConfig(80..89, "ZTE", listOf("zte")),
        BrandConfig(90..90, "JianGuo", listOf("smartisan", "jianguo")),
        BrandConfig(110..119, "Hisense", listOf("hisense")),
        BrandConfig(120..120, "ASUS", listOf("asus")),
        BrandConfig(121..121, "ROG", listOf("rog")),
        BrandConfig(70..79, "Samsung", listOf("samsung")),
        BrandConfig(100..109, "Lenovo", listOf("lenovo"))
    )

    fun getLocalBrandId(): Int {
        val settings = AppSettings(MyApplication.getInstance())
        
        // Developer Overwrite Priority
        if (settings.devMode && settings.overwriteBrandId) {
            return settings.customBrandId
        }
        
        // Manual Selection Priority
        if (settings.brandId != -1) {
            return settings.brandId
        }
        
        // Automatic Detection (Single Pass through Registry)
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        
        return BRAND_REGISTRY.firstOrNull { config ->
            config.searchKeys.any { key -> 
                brand.contains(key) || manufacturer.contains(key) 
            }
        }?.idRange?.first ?: 0
    }

    fun deviceNameById(id: Int): String {
        val config = BRAND_REGISTRY.firstOrNull { id in it.idRange }
        return config?.name ?: "Custom ($id)"
    }

    fun getBrandList(): List<Pair<Int, String>> {
        val list = mutableListOf(-1 to "Auto")
        list.addAll(
            BRAND_REGISTRY
                .filter { it.isPrimary }
                .map { it.idRange.first to it.name }
        )
        return list
    }

    fun bleByteToBrandId(byte: Byte): Int {
        val id = byte.toInt() and 0xFF
        return if (id == 114) 114514 else id
    }

    fun getRandomChars(len: Int): String {
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray()
        val sb = StringBuilder()
        val rand = Random()
        repeat(len) {
            sb.append(alphabet[rand.nextInt(alphabet.size)])
        }
        return sb.toString()
    }
}
