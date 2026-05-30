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
        BrandConfig(20..29, "vivo", listOf("vivo")),
        BrandConfig(30..30, "Xiaomi", listOf("xiaomi", "redmi")),
        BrandConfig(32..32, "Black Shark", listOf("blackshark")),
        BrandConfig(41..45, "OnePlus", listOf("oneplus")),
        BrandConfig(50..59, "Meizu", listOf("meizu")),
        BrandConfig(60..69, "Nubia", listOf("nubia", "redmagic")),
        BrandConfig(70..79, "Samsung", listOf("samsung")) ,
        BrandConfig(80..89, "ZTE", listOf("zte")),
        BrandConfig(90..90, "JianGuo", listOf("smartisan", "jianguo")),
        BrandConfig(100..109, "Lenovo", listOf("lenovo")),
        BrandConfig(160..169, "ROG", listOf("rog")),
        BrandConfig(170..179, "Hisense", listOf("hisense")),
        //OPPO车机？ BrandConfig(200..200, "T"),
        /* ColorOS系的果子互传ID
        BrandConfig(800..800, "iPhone", listOf("iphone"), false),
        BrandConfig(801..801, "iPad", listOf("ipad"), false),
        BrandConfig(802..802, "Mac", listOf("macintosh", "macbook"), false)
         */
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
        if (id == 114) return 114514
        if (id == 32) {
            // Collision: Black Shark (32) and iPhone (800)
            // Return 32 for now, as it's the more common case for OShare
            return 32
        }
        return id
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
