import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * Local development only: mints an RS256 JWT that the "dev" profile accepts.
 *
 * <pre>
 *   java scripts/DevToken.java --sub alice --scope payments
 *   java scripts/DevToken.java --sub rita --scope reviews --minutes 30
 * </pre>
 *
 * The first run creates a key pair in .dev-keys/ (git-ignored): public.pem for payguard, private.pk8 for this
 * script. Real deployments use the "prod" profile and the organisation's identity provider instead.
 */
public class DevToken {

    public static void main(String[] args) throws Exception {
        String sub = arg(args, "--sub", "alice");
        String scope = arg(args, "--scope", "payments");
        long minutes = Long.parseLong(arg(args, "--minutes", "60"));
        Path dir = Path.of(arg(args, "--keys", ".dev-keys"));
        PrivateKey key = loadOrCreate(dir);

        long now = Instant.now().getEpochSecond();
        String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = b64(String.format("{\"iss\":\"payguard-dev\",\"sub\":\"%s\",\"scope\":\"%s\",\"iat\":%d,"
                + "\"exp\":%d}", json(sub), json(scope), now, now + minutes * 60));
        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initSign(key);
        rsa.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(rsa.sign());
        System.out.println(header + "." + payload + "." + signature);
    }

    private static PrivateKey loadOrCreate(Path dir) throws Exception {
        Path priv = dir.resolve("private.pk8");
        Path pub = dir.resolve("public.pem");
        if (!Files.exists(priv)) {
            Files.createDirectories(dir);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            Files.write(priv, pair.getPrivate().getEncoded());
            String pem = "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(pair.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n";
            Files.writeString(pub, pem, StandardCharsets.US_ASCII);
            System.err.println("created " + priv + " and " + pub);
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv)));
    }

    private static String arg(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String json(String s) {
        if (!s.matches("[A-Za-z0-9 ._@-]{1,64}")) {
            throw new IllegalArgumentException("use 1 to 64 letters, digits, spaces or ._@- : " + s);
        }
        return s;
    }
}
