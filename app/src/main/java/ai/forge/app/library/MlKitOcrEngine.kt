package ai.forge.app.library

import ai.forge.documents.OcrEngine
import ai.forge.documents.PageText
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * OCR ML Kit, entièrement sur l'appareil. Lit bien le texte imprimé ; les lettrages de bulles
 * très stylisés sont moins fiables — c'est pourquoi chaque résultat reste une piste à vérifier.
 * Les images sont sous-échantillonnées pour limiter la mémoire (pages de comics ≈ 2–4 Mpx).
 */
class MlKitOcrEngine(private val maxSide: Int = 2_000) : OcrEngine {
    // Créé au premier usage : ni coût au démarrage de l'app, ni dépendance à l'initialisation de ML Kit.
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(image: ByteArray): PageText {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return PageText(0, "", 0.0)
        try {
            val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
            // Ordre de lecture approximatif : blocs triés de haut en bas puis de gauche à droite.
            val text = result.textBlocks.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                .joinToString("\n") { it.text.replace('\n', ' ') }
            return PageText(0, text, null)
        } finally { bmp.recycle() }
    }
}
