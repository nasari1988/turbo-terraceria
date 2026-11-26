package tw.nekomimi.nekogram.nekomod.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * NekoMod: OCR en el teléfono con ML Kit (reconocimiento latino).
 */
public final class TextRecognizerHelper {

    public static final class Line {
        public final String text;
        public final Rect box; // coordenadas del bitmap, puede ser null

        public Line(String text, Rect box) {
            this.text = text;
            this.box = box;
        }
    }

    public interface Callback {
        void onResult(List<Line> lines);

        void onError(Exception e);
    }

    private TextRecognizerHelper() {
    }

    public static void recognize(Bitmap bitmap, final Callback callback) {
        if (bitmap == null || bitmap.isRecycled()) {
            callback.onError(new IllegalArgumentException("bitmap null o reciclado"));
            return;
        }
        try {
            final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            recognizer.process(image).addOnSuccessListener(text -> {
                ArrayList<Line> lines = new ArrayList<>();
                for (Text.TextBlock block : text.getTextBlocks()) {
                    for (Text.Line line : block.getLines()) {
                        String t = line.getText();
                        if (t != null && !t.trim().isEmpty()) {
                            lines.add(new Line(t, line.getBoundingBox()));
                        }
                    }
                }
                recognizer.close();
                callback.onResult(lines);
            }).addOnFailureListener(e -> {
                recognizer.close();
                callback.onError(e);
            });
        } catch (Throwable t) {
            callback.onError(t instanceof Exception ? (Exception) t : new Exception(t));
        }
    }
}
