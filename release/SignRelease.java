import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.zip.*;

/**
 * GL: signs release assets so the in-game updater (mindustry.net.UpdateVerifier) accepts them.
 * Runs with a plain JDK 17+, no build needed:
 *
 *   java release/SignRelease.java keygen <key file>             - makes a new key pair, prints the public key
 *   java release/SignRelease.java sign <key file> <kind> <file>  - writes <file>.sig next to the file
 *
 * kind is desktop, steam or android: a client only takes a build signed for its own kind.
 * The key file holds the PRIVATE key. Keep it outside the repository and back it up: without it no client
 * accepts an update any more.
 */
public class SignRelease{
    static final String header = "GL-UPDATE-1";

    public static void main(String[] args) throws Exception{
        if(args.length >= 2 && args[0].equals("keygen")){
            keygen(Paths.get(args[1]));
        }else if(args.length >= 4 && args[0].equals("sign")){
            sign(Paths.get(args[1]), args[2], Paths.get(args[3]));
        }else{
            System.err.println("usage: keygen <key file> | sign <key file> <desktop|steam|android> <file>");
            System.exit(1);
        }
    }

    static void keygen(Path keyFile) throws Exception{
        if(Files.exists(keyFile)) throw new IOException(keyFile + " already exists, refusing to overwrite a release key");
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Base64.Encoder b64 = Base64.getEncoder();
        String text = "private=" + b64.encodeToString(pair.getPrivate().getEncoded()) + "\n"
            + "public=" + b64.encodeToString(pair.getPublic().getEncoded()) + "\n";
        Files.writeString(keyFile, text, StandardCharsets.UTF_8);
        System.out.println("public key (raw, put into UpdateVerifier.publicKey): " + b64.encodeToString(rawPublic(pair.getPublic())));
    }

    static byte[] rawPublic(PublicKey key){
        byte[] x509 = key.getEncoded();
        return Arrays.copyOfRange(x509, x509.length - 32, x509.length);
    }

    static void sign(Path keyFile, String kind, Path file) throws Exception{
        if(!List.of("desktop", "steam", "android").contains(kind)) throw new IllegalArgumentException("unknown kind " + kind);

        Properties key = new Properties();
        try(Reader r = Files.newBufferedReader(keyFile, StandardCharsets.UTF_8)){ key.load(r); }
        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        PrivateKey priv = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.getProperty("private"))));
        PublicKey pub = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key.getProperty("public"))));

        long buildTime = buildTime(file);
        if(buildTime <= 0) throw new IOException("no buildTime in version.properties of " + file);

        String body = header + "\n"
            + "kind=" + kind + "\n"
            + "buildTime=" + buildTime + "\n"
            + "size=" + Files.size(file) + "\n"
            + "sha256=" + sha256(file) + "\n";

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(priv);
        signer.update(body.getBytes(StandardCharsets.UTF_8));
        byte[] sig = signer.sign();

        Signature check = Signature.getInstance("Ed25519");
        check.initVerify(pub);
        check.update(body.getBytes(StandardCharsets.UTF_8));
        if(!check.verify(sig)) throw new IllegalStateException("private and public key in " + keyFile + " do not match");

        Path out = file.resolveSibling(file.getFileName() + ".sig");
        Files.writeString(out, body + "sig=" + Base64.getEncoder().encodeToString(sig) + "\n", StandardCharsets.UTF_8);
        System.out.println("signed " + kind + " build " + buildTime + " -> " + out);
        System.out.println("upload it next to the file under the same name plus .sig, e.g. MindustryGL.jar.sig");
    }

    /** version.properties sits at the jar root and under assets/ in the APK. */
    static long buildTime(Path file) throws IOException{
        try(ZipFile zip = new ZipFile(file.toFile())){
            ZipEntry entry = zip.getEntry("version.properties");
            if(entry == null) entry = zip.getEntry("assets/version.properties");
            if(entry == null) return 0;
            Properties props = new Properties();
            try(InputStream in = zip.getInputStream(entry)){ props.load(in); }
            return Long.parseLong(props.getProperty("buildTime", "0").trim());
        }
    }

    static String sha256(Path file) throws Exception{
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try(InputStream in = Files.newInputStream(file)){
            byte[] buf = new byte[1 << 16];
            int n;
            while((n = in.read(buf)) > 0) digest.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
