package com.openpositioning.PositionMe.data.ml;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import com.openpositioning.PositionMe.domain.GeoPosition;
import com.openpositioning.PositionMe.domain.WifiFingerprint;
import com.openpositioning.PositionMe.domain.WifiPositionPredictor;
import org.json.JSONException;
import org.json.JSONObject;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Optional asset-backed inference adapter. It never reads the SensorFusion singleton. */
public final class TfliteWifiPositionPredictor implements WifiPositionPredictor {
    private Interpreter interpreter;
    private final WifiModelSpec spec;
    private final Map<String, Integer> dictionary;

    public TfliteWifiPositionPredictor(Context context, String modelAsset,
                                       String dictionaryAsset, WifiModelSpec spec) throws IOException {
        this.spec = Objects.requireNonNull(spec);
        this.dictionary = loadDictionary(context, dictionaryAsset);
        Interpreter created = null;
        try {
            created = new Interpreter(loadModel(context, modelAsset));
            int n = spec.getTopN();
            if (created.getInputTensorCount() != 2 || created.getOutputTensorCount() != 1
                    || created.getInputTensor(0).dataType() != DataType.FLOAT32
                    || created.getInputTensor(1).dataType() != DataType.INT32
                    || created.getOutputTensor(0).dataType() != DataType.FLOAT32
                    || !java.util.Arrays.equals(created.getInputTensor(0).shape(), new int[]{1, n})
                    || !java.util.Arrays.equals(created.getInputTensor(1).shape(), new int[]{1, n})
                    || !java.util.Arrays.equals(created.getOutputTensor(0).shape(), new int[]{1, 2})) {
                throw new IllegalArgumentException("Model tensors do not match the Wi-Fi feature contract");
            }
            interpreter = created;
        } catch (RuntimeException exception) {
            if (created != null) created.close();
            throw new IOException("Could not initialize Wi-Fi inference model", exception);
        }
    }

    @Override public synchronized GeoPosition predict(WifiFingerprint fingerprint) {
        if (interpreter == null) throw new IllegalStateException("Predictor is closed");
        List<WifiFingerprint.AccessPoint> strongest = new ArrayList<>(
                Objects.requireNonNull(fingerprint).getAccessPoints());
        strongest.sort(Comparator.comparingInt(WifiFingerprint.AccessPoint::getRssiDbm).reversed());
        int n = spec.getTopN();
        float[][] rssi = new float[1][n];
        ByteBuffer indices = ByteBuffer.allocateDirect(n * Integer.BYTES).order(ByteOrder.nativeOrder());
        for (int i = 0; i < n; i++) {
            WifiFingerprint.AccessPoint point = i < strongest.size() ? strongest.get(i) : null;
            indices.putInt(point == null ? 0 : dictionary.getOrDefault(point.getIdentifier(), 0));
            rssi[0][i] = point == null ? -1.0f : point.getRssiDbm() / 100.0f;
        }
        indices.rewind();
        float[][] output = new float[1][2];
        Map<Integer, Object> outputs = new HashMap<>();
        outputs.put(0, output);
        interpreter.runForMultipleInputsOutputs(new Object[]{rssi, indices}, outputs);
        return new GeoPosition(spec.latitude(output[0][0]), spec.longitude(output[0][1]), 0.0);
    }

    @Override public synchronized void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }

    private static MappedByteBuffer loadModel(Context context, String asset) throws IOException {
        try (AssetFileDescriptor descriptor = context.getAssets().openFd(asset);
             FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor());
             FileChannel channel = stream.getChannel()) {
            return channel.map(FileChannel.MapMode.READ_ONLY, descriptor.getStartOffset(), descriptor.getDeclaredLength());
        }
    }

    private static Map<String, Integer> loadDictionary(Context context, String asset) throws IOException {
        try (InputStream input = context.getAssets().open(asset);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            JSONObject object = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            Map<String, Integer> dictionary = new HashMap<>();
            Iterator<String> names = object.keys();
            while (names.hasNext()) {
                String name = names.next();
                int index = object.getInt(name);
                if (index < 0) throw new IOException("Negative BSSID feature index");
                dictionary.put(name, index);
            }
            return java.util.Collections.unmodifiableMap(dictionary);
        } catch (JSONException exception) {
            throw new IOException("Invalid Wi-Fi feature dictionary", exception);
        }
    }
}
