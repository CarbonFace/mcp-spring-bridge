import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.UUID;

/** Explicit local-demo command only. It is outside the application source set. */
class InitializeDemoKeys {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Usage: java InitializeDemoKeys.java <new-directory-under-existing-parent>");
        }
        Path directory = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path parent = directory.getParent();
        if (parent == null || !Files.isDirectory(parent) || !parent.toRealPath().equals(parent)
                || Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Use a new directory under an existing real parent; existing paths are never reused");
        }
        boolean posix = Files.getFileStore(parent).supportsFileAttributeView("posix");
        if (posix) Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        else Files.createDirectory(directory);
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        var pair = generator.generateKeyPair();
        var key = (RSAPrivateCrtKey) pair.getPrivate();
        String jwk = "{\"kty\":\"RSA\",\"kid\":\"local-demo-" + UUID.randomUUID()
                + "\",\"n\":\"" + encode(key.getModulus()) + "\",\"e\":\"" + encode(key.getPublicExponent())
                + "\",\"d\":\"" + encode(key.getPrivateExponent()) + "\",\"p\":\"" + encode(key.getPrimeP())
                + "\",\"q\":\"" + encode(key.getPrimeQ()) + "\",\"dp\":\"" + encode(key.getPrimeExponentP())
                + "\",\"dq\":\"" + encode(key.getPrimeExponentQ()) + "\",\"qi\":\"" + encode(key.getCrtCoefficient()) + "\"}";
        byte[] storageKey = new byte[32];
        new SecureRandom().nextBytes(storageKey);
        write(directory.resolve("signing.jwk"), jwk, posix);
        write(directory.resolve("storage.key"), Base64.getEncoder().encodeToString(storageKey), posix);
        java.util.Arrays.fill(storageKey, (byte) 0);
        System.out.println("Local demo keys created. These paths contain secrets; do not commit the directory.");
        System.out.println("MCP_DEMO_SIGNING_JWK=" + directory.resolve("signing.jwk").toUri());
        System.out.println("MCP_DEMO_STORAGE_KEY=" + directory.resolve("storage.key").toUri());
    }

    private static void write(Path file, String value, boolean posix) throws Exception {
        if (posix) Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        else Files.createFile(file);
        Files.writeString(file, value, StandardCharsets.UTF_8, StandardOpenOption.WRITE);
    }

    private static String encode(BigInteger integer) {
        byte[] bytes = integer.toByteArray();
        if (bytes[0] == 0) bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
