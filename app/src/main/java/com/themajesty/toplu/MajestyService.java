package com.themajesty.toplu;

import android.accessibilityservice.AccessibilityService;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MajestyService extends AccessibilityService {

    private static final String TARGET = "com.trendmoda";
    private static final String SITE = "https://modayakamoz.com/";

    private static final Pattern PRICE_PATTERN =
            Pattern.compile("(?i)(Fiyat\\s*[:：]?\\s*)([0-9][0-9.,]*)\\s*₺?");

    private static final Pattern PRODUCT_CODE_PATTERN =
            Pattern.compile("(?i)ürün\\s*kodu\\s*[:：]?\\s*(\\d{4,10})");

    private static final Pattern IMG_TAG_PATTERN =
            Pattern.compile("(?is)<img\\b[^>]*>");

    private static final Pattern ATTR_PATTERN =
            Pattern.compile("(?is)(src|data-src|data-original|data-lazy|data-zoom-image|data-large|href)\\s*=\\s*[\"']([^\"']+)[\"']");

    WindowManager wm;
    Button downloadBtn;
    Button copyBtn;
    Handler h = new Handler(Looper.getMainLooper());

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        h.postDelayed(() -> {
            try {
                if (Settings.canDrawOverlays(this)) showButtons();
            } catch (Throwable ignored) {}
        }, 1200);
    }

    private void showButtons() {
        if (downloadBtn != null || copyBtn != null) return;

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        // SOLDa sabit TOPLU İNDİR
        downloadBtn = new Button(this);
        downloadBtn.setText("TOPLU\nİNDİR");
        downloadBtn.setTextColor(Color.WHITE);
        downloadBtn.setTextSize(13);
        downloadBtn.setBackgroundColor(Color.rgb(255, 122, 24));

        WindowManager.LayoutParams left = new WindowManager.LayoutParams(
                210, 150,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        left.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        left.x = 12;

        // SAĞda sabit COPY
        copyBtn = new Button(this);
        copyBtn.setText("COPY");
        copyBtn.setTextColor(Color.WHITE);
        copyBtn.setTextSize(15);
        copyBtn.setBackgroundColor(Color.rgb(126, 45, 210));

        WindowManager.LayoutParams right = new WindowManager.LayoutParams(
                200, 125,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        right.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        right.x = 12;
        right.y = 300;

        downloadBtn.setOnClickListener(v -> {
            showButtonFeedback(downloadBtn, "İNDİRİLİYOR...", "TOPLU\nİNDİR", Color.rgb(220, 90, 0));
            startOriginalImageDownload();
        });
        copyBtn.setOnClickListener(v -> {
            showButtonFeedback(copyBtn, "KOPYALANIYOR...", "COPY", Color.rgb(92, 28, 170));
            copyCustomizedDescription();
        });

        wm.addView(downloadBtn, left);
        wm.addView(copyBtn, right);
    }

    /**
     * v2.4:
     * - screenshot YOK
     * - swipe YOK
     * - e-Kolay ekranından ürün kodu + ürün adı alınır
     * - Moda Yakamoz ürün sayfası arka planda açılır
     * - yalnızca alt="Product image" olan gerçek galeri resimleri bulunur
     * - JPG/JPEG/PNG/WEBP dosyaları DownloadManager ile ORİJİNAL olarak indirilir
     */
    private void startOriginalImageDownload() {
        AccessibilityNodeInfo root = getRootInActiveWindow();

        if (root == null ||
                root.getPackageName() == null ||
                !TARGET.contentEquals(root.getPackageName())) {
            toast("Önce e-Kolay Depo'da ürün ekranını aç.");
            return;
        }

        final String allText = collectAllText(root);
        final String productCode = findProductCode(allText);
        final String productTitle = findProductTitle(allText);

        if (productCode.isEmpty()) {
            toast("Ürün kodu bulunamadı.");
            return;
        }

        if (productTitle.isEmpty()) {
            toast("Ürün adı bulunamadı.");
            return;
        }

        toast("Orijinal ürün görselleri hazırlanıyor…");

        new Thread(() -> {
            try {
                String slug = slugify(productTitle);
                String productUrl = SITE + "urun-" + productCode + "&" + slug;

                String html = httpGet(productUrl);

                Set<String> imageUrls = parseProductImageUrls(html);

                // Bazı ürün sayfalarında slug farklı yazılmış olabilir.
                // İlk istek boş dönerse ürün kodu + açıklamadan alternatif küçük slug denenir.
                if (imageUrls.isEmpty()) {
                    String altSlug = slugify(removeLeadingGender(productTitle));
                    if (!altSlug.equals(slug)) {
                        productUrl = SITE + "urun-" + productCode + "&" + altSlug;
                        html = httpGet(productUrl);
                        imageUrls = parseProductImageUrls(html);
                    }
                }

                final Set<String> finalUrls = imageUrls;
                final String finalProductUrl = productUrl;

                h.post(() -> {
                    if (finalUrls.isEmpty()) {
                        toast("Orijinal görsel bulunamadı. Ürün sayfası eşleşmedi.");
                        return;
                    }

                    int index = 1;
                    int queued = 0;
                    for (String imageUrl : finalUrls) {
                        if (enqueueDownload(
                                imageUrl,
                                productCode,
                                index++,
                                finalProductUrl)) {
                            queued++;
                        }
                    }

                    toast(queued + " orijinal görsel indiriliyor ✓");
                });

            } catch (Throwable e) {
                h.post(() -> toast("Orijinal görsel indirme başarısız."));
            }
        }).start();
    }

    private String httpGet(String urlString) throws Exception {
        HttpURLConnection conn = null;
        BufferedReader reader = null;

        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            conn.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.7");

            int code = conn.getResponseCode();

            InputStream stream =
                    code >= 200 && code < 400
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            if (stream == null) throw new Exception("HTTP " + code);

            reader = new BufferedReader(
                    new InputStreamReader(stream, "UTF-8"));

            StringBuilder html = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                html.append(line).append('\n');
            }

            return html.toString();

        } finally {
            try {
                if (reader != null) reader.close();
            } catch (Throwable ignored) {}

            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Sadece ürün galerisindeki <img alt="Product image" ...> etiketleri alınır.
     * Böylece önerilen ürünler / logo / ikonlar indirilmez.
     * Beden tablosu da Product image olduğundan dahil edilir.
     */
    private Set<String> parseProductImageUrls(String html) {
        Set<String> out = new LinkedHashSet<>();

        Matcher tagMatcher = IMG_TAG_PATTERN.matcher(html);

        while (tagMatcher.find()) {
            String tag = tagMatcher.group();

            String lower = tag.toLowerCase(Locale.ROOT);

            if (!(lower.contains("alt=\"product image\"") ||
                    lower.contains("alt='product image'"))) {
                continue;
            }

            Matcher attrMatcher = ATTR_PATTERN.matcher(tag);

            while (attrMatcher.find()) {
                String value = htmlDecode(attrMatcher.group(2));

                if (value == null || value.trim().isEmpty()) continue;

                value = value.trim();

                if (value.startsWith("//")) {
                    value = "https:" + value;
                } else if (value.startsWith("/")) {
                    value = "https://modayakamoz.com" + value;
                }

                String check = value.toLowerCase(Locale.ROOT);

                if (!check.contains("/resimler/")) continue;

                if (!(check.contains(".jpg") ||
                        check.contains(".jpeg") ||
                        check.contains(".png") ||
                        check.contains(".webp"))) {
                    continue;
                }

                out.add(value);
                break; // her Product image tag'ından tek orijinal kaynak
            }
        }

        return out;
    }

    private String htmlDecode(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&")
                .replace("&#38;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private boolean enqueueDownload(
            String imageUrl,
            String productCode,
            int index,
            String referer) {

        try {
            DownloadManager dm =
                    (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

            DownloadManager.Request req =
                    new DownloadManager.Request(Uri.parse(imageUrl));

            req.setAllowedOverMetered(true);
            req.setAllowedOverRoaming(true);
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            String ext = extension(imageUrl);
            String fileName =
                    productCode + "_" +
                            String.format(Locale.US, "%02d", index) +
                            ext;

            req.setTitle(fileName);
            req.setDescription("Moda Yakamoz orijinal ürün görseli");
            req.addRequestHeader(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36");
            req.addRequestHeader("Referer", referer);

            req.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "THE_MAJESTY/" + productCode + "/" + fileName);

            dm.enqueue(req);
            return true;

        } catch (Throwable ignored) {
            return false;
        }
    }

    private String extension(String url) {
        String x = url.toLowerCase(Locale.ROOT);
        if (x.contains(".png")) return ".png";
        if (x.contains(".webp")) return ".webp";
        if (x.contains(".jpeg")) return ".jpeg";
        return ".jpg";
    }

    private String findProductCode(String text) {
        Matcher m = PRODUCT_CODE_PATTERN.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Açıklama bloğunda fiyat ile beden arasındaki ürün adı bulunur.
     * Emoji olsun/olmasın çalışacak şekilde sadeleştirilir.
     */
    private String findProductTitle(String text) {
        if (text == null) return "";

        String normalized = text
                .replace("\r", "\n")
                .replace('\u00A0', ' ');

        String[] lines = normalized.split("\\n+");

        boolean priceSeen = false;

        for (String line : lines) {
            String x = stripKnownEmoji(line.trim());
            if (x.isEmpty()) continue;

            String lower = x.toLowerCase(Locale.ROOT);

            if (lower.contains("fiyat")) {
                priceSeen = true;
                continue;
            }

            if (priceSeen) {
                if (lower.startsWith("beden") ||
                        lower.startsWith("kumaş") ||
                        lower.startsWith("kumas") ||
                        lower.contains("modelin ölçüleri")) {
                    break;
                }

                if (!lower.contains("ürün kodu") &&
                        !lower.matches("^[0-9.,₺\\s]+$")) {
                    return x;
                }
            }
        }

        // Tek TextView fallback
        Matcher m = Pattern.compile(
                "(?is)fiyat\\s*[:：]?\\s*[0-9.,]+\\s*₺?\\s*(.*?)\\s*(?:🌈?\\s*beden\\s*:|beden\\s*:)")
                .matcher(normalized);

        if (m.find()) {
            return stripKnownEmoji(m.group(1).trim());
        }

        return "";
    }

    private String stripKnownEmoji(String s) {
        return s.replace("❤️", "")
                .replace("❤", "")
                .replace("🎽", "")
                .replace("👉", "")
                .replace("🌈", "")
                .replace("👕", "")
                .trim();
    }

    private String removeLeadingGender(String title) {
        String x = title.trim();
        if (x.toLowerCase(Locale.ROOT).startsWith("kadın ")) {
            return x.substring(6).trim();
        }
        return x;
    }

    /**
     * Moda Yakamoz slug üretimi:
     * "Kadın omuzlardan açık..." -> "Kadin-omuzlardan-acik-..."
     */
    private String slugify(String input) {
        String x = input == null ? "" : input.trim();

        x = x.replace("ı", "i")
                .replace("İ", "I")
                .replace("ş", "s")
                .replace("Ş", "S")
                .replace("ğ", "g")
                .replace("Ğ", "G")
                .replace("ü", "u")
                .replace("Ü", "U")
                .replace("ö", "o")
                .replace("Ö", "O")
                .replace("ç", "c")
                .replace("Ç", "C");

        x = Normalizer.normalize(x, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");

        x = x.replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");

        if (x.isEmpty()) return x;

        // Sitedeki örnek sluglarda ilk kelime baş harfli, devamı küçük.
        String lower = x.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private String collectAllText(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        appendTextPreorder(root, sb);
        return sb.toString();
    }

    private void appendTextPreorder(
            AccessibilityNodeInfo node,
            StringBuilder out) {

        if (node == null) return;

        CharSequence text = node.getText();

        if (text != null) {
            String s = text.toString().trim();
            if (!s.isEmpty()) out.append(s).append('\n');
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) appendTextPreorder(child, out);
        }
    }

    // ------------------------------------------------------------
    // COPY v2.2 DAVRANIŞI KORUNDU
    // ------------------------------------------------------------

    private void copyCustomizedDescription() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();

            if (root == null ||
                    root.getPackageName() == null ||
                    !TARGET.contentEquals(root.getPackageName())) {
                toast("Önce e-Kolay Depo'da ürün ekranını aç.");
                return;
            }

            String raw = findDescriptionBlock(root);

            if (raw == null || raw.trim().isEmpty()) {
                toast("Ürün açıklaması bulunamadı. Açıklama bölümü ekranda görünsün.");
                return;
            }

            String result = transformDescription(raw);

            if (result == null || result.trim().isEmpty()) {
                toast("Kopyalanacak ürün metni oluşturulamadı.");
                return;
            }

            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

            clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                            "THE MAJESTY Ürün Açıklaması",
                            result));

            toast("COPY ✓  Fiyat +200₺ ve yeni açıklama panoya kopyalandı.");

        } catch (Throwable e) {
            toast("COPY işlemi başarısız.");
        }
    }

    private String findDescriptionBlock(AccessibilityNodeInfo root) {
        ArrayList<AccessibilityNodeInfo> stack = new ArrayList<>();
        stack.add(root);

        String best = "";

        while (!stack.isEmpty()) {
            AccessibilityNodeInfo node =
                    stack.remove(stack.size() - 1);

            CharSequence text = node.getText();

            if (text != null) {
                String s = text.toString().trim();
                String lower = s.toLowerCase(Locale.ROOT);

                if (lower.contains("ürün kodu") &&
                        lower.contains("fiyat") &&
                        (lower.contains("kumaş") ||
                                lower.contains("kumas"))) {

                    if (s.length() > best.length()) best = s;
                }
            }

            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) stack.add(child);
            }
        }

        if (!best.isEmpty()) return best;

        String text = collectAllText(root);

        int start = indexOfIgnoreCase(text, "Ürün kodu");
        if (start < 0) start =
                indexOfIgnoreCase(text, "Urun kodu");

        return start >= 0
                ? text.substring(start)
                : text;
    }

    private String transformDescription(String raw) {
        String text = raw
                .replace("\r", "\n")
                .replace('\u00A0', ' ')
                .trim();

        text = text
                .replaceAll("(?i)\\s+(🎽?\\s*Ürün\\s*kodu\\s*:)", "\n$1")
                .replaceAll("(?i)\\s+(👉?\\s*Fiyat\\s*:)", "\n$1")
                .replaceAll("(?i)\\s+(🌈?\\s*Beden\\s*:)", "\n$1")
                .replaceAll("(?i)\\s+(👕?\\s*Kumaş\\s*:)", "\n$1")
                .replaceAll("(?i)\\s+(🐒?\\s*Modelin\\s*Ölçüleri\\s*:)", "\n$1")
                .replaceAll("(?i)\\s+(📏?\\s*Modelin\\s*üzerindeki)", "\n$1")
                .replaceAll("(?i)\\s+(✔\\s*Sipariş)", "\n$1")
                .replaceAll("(?i)\\s+(🤖\\s*Yapay\\s*Zeka)", "\n$1");

        String[] lines = text.split("\\n+");

        ArrayList<String> result =
                new ArrayList<>();

        boolean started = false;
        boolean fabricFound = false;

        for (String line : lines) {
            String x = line.trim();
            if (x.isEmpty()) continue;

            String lower =
                    x.toLowerCase(Locale.ROOT);

            if (!started) {
                if (lower.contains("ürün kodu") ||
                        lower.contains("urun kodu")) {
                    started = true;
                } else {
                    continue;
                }
            }

            if (lower.contains("modelin ölçüleri") ||
                    lower.contains("modelin olculeri") ||
                    lower.contains("modelin üzerindeki") ||
                    lower.contains("modelin uzerindeki") ||
                    lower.contains("sipariş ve bilgi") ||
                    lower.contains("siparis ve bilgi") ||
                    lower.contains("yapay zeka destekli")) {
                break;
            }

            Matcher priceMatcher =
                    PRICE_PATTERN.matcher(x);

            if (priceMatcher.find()) {
                double original =
                        parsePrice(priceMatcher.group(2));

                double newPrice =
                        original + 200.0;

                x = priceMatcher.replaceFirst(
                        Matcher.quoteReplacement(
                                priceMatcher.group(1) +
                                        formatPrice(newPrice) +
                                        " ₺"));
            }

            result.add(x);

            if (lower.contains("kumaş") ||
                    lower.contains("kumas")) {

                fabricFound = true;

                result.add("Kargo Ücretsizdir..");
                result.add(
                        "*KAPIDA ÖDEME VE HAVALE&EFT İLE ÖDEME MEVCUTTUR*");

                break;
            }
        }

        if (!started || !fabricFound) return "";

        return String.join("\n", result).trim();
    }

    private double parsePrice(String value) {
        try {
            String x = value.trim();

            int dot = x.lastIndexOf('.');
            int comma = x.lastIndexOf(',');

            if (dot >= 0 && comma >= 0) {
                if (comma > dot) {
                    x = x.replace(".", "")
                            .replace(",", ".");
                } else {
                    x = x.replace(",", "");
                }
            } else if (comma >= 0) {
                int decimals =
                        x.length() - comma - 1;

                if (decimals == 2) {
                    x = x.replace(",", ".");
                } else {
                    x = x.replace(",", "");
                }
            } else if (dot >= 0) {
                int decimals =
                        x.length() - dot - 1;

                if (decimals != 2) {
                    x = x.replace(".", "");
                }
            }

            return Double.parseDouble(x);

        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    private String formatPrice(double price) {
        return String.format(
                Locale.US,
                "%.2f",
                price);
    }

    private int indexOfIgnoreCase(
            String source,
            String target) {

        return source.toLowerCase(Locale.ROOT)
                .indexOf(
                        target.toLowerCase(Locale.ROOT));
    }

    private void showButtonFeedback(
            Button button,
            String activeText,
            String normalText,
            int activeColor) {

        try {
            button.setEnabled(false);
            button.setText(activeText);
            button.setBackgroundColor(activeColor);
            button.setAlpha(0.82f);

            button.animate()
                    .scaleX(0.92f)
                    .scaleY(0.92f)
                    .setDuration(90)
                    .withEndAction(() ->
                            button.animate()
                                    .scaleX(1.0f)
                                    .scaleY(1.0f)
                                    .setDuration(120)
                                    .start())
                    .start();

            h.postDelayed(() -> {
                try {
                    button.setText(normalText);
                    button.setAlpha(1.0f);

                    if (button == downloadBtn) {
                        button.setBackgroundColor(Color.rgb(255, 122, 24));
                    } else if (button == copyBtn) {
                        button.setBackgroundColor(Color.rgb(126, 45, 210));
                    }

                    button.setEnabled(true);
                } catch (Throwable ignored) {}
            }, 900);

        } catch (Throwable ignored) {}
    }

    private void toast(String text) {
        Toast.makeText(
                this,
                text,
                Toast.LENGTH_LONG).show();
    }

    @Override
    public void onDestroy() {
        try {
            if (downloadBtn != null && wm != null)
                wm.removeView(downloadBtn);
        } catch (Throwable ignored) {}

        try {
            if (copyBtn != null && wm != null)
                wm.removeView(copyBtn);
        } catch (Throwable ignored) {}

        super.onDestroy();
    }
}
