import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Signs an APK with APK Signature Scheme v2 (+ v1 when minSdk < 24) using Google's apksig library
 * (com.android.tools.build:apksig, Maven Central) and verifies the result.
 *
 * usage: ApkSign <in.apk> <out.apk> <keystore.p12> <storepass> <alias> <minSdk>
 */
public class ApkSign {
    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            System.err.println("usage: ApkSign <in.apk> <out.apk> <keystore.p12> <storepass> <alias> <minSdk>");
            System.exit(2);
        }
        File in = new File(args[0]);
        File out = new File(args[1]);
        char[] pass = args[3].toCharArray();
        int minSdk = Integer.parseInt(args[5]);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(args[2])) {
            ks.load(fis, pass);
        }
        PrivateKey key = (PrivateKey) ks.getKey(args[4], pass);
        Certificate[] chain = ks.getCertificateChain(args[4]);
        List<X509Certificate> certs = new ArrayList<>();
        for (Certificate c : chain) certs.add((X509Certificate) c);

        ApkSigner.SignerConfig signer =
                new ApkSigner.SignerConfig.Builder("REDPHONE", key, certs).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(in)
                .setOutputApk(out)
                .setMinSdkVersion(minSdk)
                // v1 (JAR) signatures are only consulted below API 24; minSdk is 26, so v2 is what
                // Android verifies. (apksig 2.3.0's v1 signer also needs a PKCS7 API newer JDKs removed.)
                .setV1SigningEnabled(minSdk < 24)
                .setV2SigningEnabled(true)
                .setCreatedBy("redphone build_apk.py")
                .build()
                .sign();

        ApkVerifier.Result res = new ApkVerifier.Builder(out).setMinCheckedPlatformVersion(minSdk).build().verify();
        for (ApkVerifier.IssueWithParams e : res.getErrors()) System.err.println("ERROR: " + e);
        for (ApkVerifier.IssueWithParams w : res.getWarnings()) System.err.println("WARNING: " + w);
        System.out.println("verified=" + res.isVerified()
                + " v1=" + res.isVerifiedUsingV1Scheme()
                + " v2=" + res.isVerifiedUsingV2Scheme());
        if (!res.isVerified()) System.exit(1);
    }
}
