package mindustry.net;

import arc.files.*;
import arc.util.*;
import mindustry.*;
import mindustry.core.*;

import java.io.*;
import java.nio.charset.*;
import java.security.*;

/**
 * GL: checks that a downloaded update was signed with the GL release key (release/SignRelease.java) before it runs.
 * The signature covers the build kind, its build time, size and SHA-256, so a release can be neither swapped for
 * another file, handed to the wrong platform, nor rolled back to an older signed build.
 */
public class UpdateVerifier{
    /** Raw Ed25519 public key of the GL release key. The private half never leaves the release machine. */
    static final String publicKey = "7Ag4D4fGWCqD+3Y7YR/TzCjke8FbVVz68UCUI3KUmLc=";
    static final String header = "GL-UPDATE-1";
    /** Largest .sig file accepted; a real one is a few hundred bytes. */
    public static final int maxSigSize = 4096;

    /** @return which signed kind this copy of the game accepts. */
    public static String expectedKind(){
        if(arc.Core.app.isAndroid()) return "android";
        if(Vars.steam || Version.isSteam) return "steam";
        return "desktop";
    }

    /** Throws with a readable reason when the file does not match the signature. */
    public static void verify(Fi file, String sigText, String kind) throws IOException{
        String[] lines = sigText.replace("\r", "").split("\n");
        StringBuilder body = new StringBuilder();
        String sig = null, sigKind = null, sha = null;
        long buildTime = -1, size = -1;

        if(lines.length == 0 || !lines[0].equals(header)) throw new IOException("unknown signature format");
        for(String line : lines){
            if(line.startsWith("sig=")){
                sig = line.substring(4).trim();
                break;
            }
            body.append(line).append('\n');
            if(line.startsWith("kind=")) sigKind = line.substring(5).trim();
            else if(line.startsWith("buildTime=")) buildTime = Strings.parseLong(line.substring(10).trim(), -1);
            else if(line.startsWith("size=")) size = Strings.parseLong(line.substring(5).trim(), -1);
            else if(line.startsWith("sha256=")) sha = line.substring(7).trim().toLowerCase();
        }
        if(sig == null || sigKind == null || sha == null || buildTime <= 0 || size < 0) throw new IOException("incomplete signature");

        byte[] sigBytes;
        try{
            sigBytes = java.util.Base64.getDecoder().decode(sig);
        }catch(IllegalArgumentException e){
            throw new IOException("bad signature encoding");
        }

        org.bouncycastle.crypto.signers.Ed25519Signer signer = new org.bouncycastle.crypto.signers.Ed25519Signer();
        signer.init(false, new org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(java.util.Base64.getDecoder().decode(publicKey), 0));
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        signer.update(bodyBytes, 0, bodyBytes.length);
        if(!signer.verifySignature(sigBytes)) throw new IOException("signature does not match the GL release key");

        if(!sigKind.equals(kind)) throw new IOException("build is signed for " + sigKind + ", this game needs " + kind);
        if(Version.buildTime > 0 && buildTime <= Version.buildTime) throw new IOException("signed build is not newer than the running one");
        if(file.length() != size) throw new IOException("file size differs from the signed one");
        if(!sha256(file).equals(sha)) throw new IOException("file hash differs from the signed one");
    }

    static String sha256(Fi file) throws IOException{
        try(InputStream in = file.read()){
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while((n = in.read(buf)) > 0) digest.update(buf, 0, n);
            StringBuilder out = new StringBuilder();
            for(byte b : digest.digest()) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        }catch(NoSuchAlgorithmException e){
            throw new IOException(e);
        }
    }
}
