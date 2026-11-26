package fr.kinwaypoint.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import fr.kinwaypoint.app.net.Member
import fr.kinwaypoint.app.net.TraccarApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

private const val AVATAR_PX = 320

/**
 * Prépare une photo choisie dans la galerie : carré centré, 320 px, JPEG.
 * Quelques dizaines de Ko : légère à envoyer et à afficher sur la carte.
 */
fun prepareAvatarJpeg(context: Context, uri: Uri): ByteArray {
    val source: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // ImageDecoder applique la rotation EXIF et réduit dès le décodage.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val small = minOf(info.size.width, info.size.height)
            if (small > AVATAR_PX * 2) {
                val ratio = (AVATAR_PX * 2).toFloat() / small
                decoder.setTargetSize((info.size.width * ratio).toInt(), (info.size.height * ratio).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= AVATAR_PX * 2) sample *= 2
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Image illisible")
    }

    val side = minOf(source.width, source.height)
    val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    val scaled = Bitmap.createScaledBitmap(square, AVATAR_PX, AVATAR_PX, true)
    return ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    }
}

/** Cache des photos : mémoire + disque (dossier cache de l'appli), par version. */
private object PhotoCache {
    private val memory = LruCache<String, Bitmap>(32)

    fun key(member: Member) = "${member.uniqueId.hashCode()}-${member.photoVersion}"

    fun get(context: Context, member: Member): Bitmap? {
        val k = key(member)
        memory.get(k)?.let { return it }
        val file = File(context.cacheDir, "avatars/$k.jpg")
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.path)?.also { memory.put(k, it) }
    }

    fun put(context: Context, member: Member, bytes: ByteArray): Bitmap? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val k = key(member)
        memory.put(k, bitmap)
        runCatching {
            val dir = File(context.cacheDir, "avatars").apply { mkdirs() }
            File(dir, "$k.jpg").writeBytes(bytes)
        }
        return bitmap
    }
}

/**
 * Photos des membres qui en ont une, chargées en arrière-plan.
 * Un membre sans photo (ou photo indisponible) n'apparaît pas : l'initiale prend le relais.
 */
@Composable
fun rememberMemberPhotos(api: TraccarApi, token: String, members: List<Member>): Map<Long, Bitmap> {
    val context = LocalContext.current
    var photos by remember { mutableStateOf<Map<Long, Bitmap>>(emptyMap()) }
    val wanted = members.filter { it.photo != null }.map { Triple(it.id, it.photo, it.photoVersion) }

    LaunchedEffect(wanted) {
        val loaded = withContext(Dispatchers.IO) {
            members.filter { it.photo != null }.mapNotNull { m ->
                val bitmap = PhotoCache.get(context, m) ?: runCatching {
                    PhotoCache.put(context, m, api.fetchPhoto(token, m.uniqueId, m.photo!!))
                }.getOrNull()
                bitmap?.let { m.id to it }
            }.toMap()
        }
        photos = loaded
    }
    return photos
}
