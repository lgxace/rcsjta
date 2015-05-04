/*******************************************************************************
 * Software Name : RCS IMS Stack
 *
 * Copyright (C) 2010 France Telecom S.A.
 * Copyright (C) 2015 Sony Mobile Communications Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * NOTE: This file has been modified by Sony Mobile Communications Inc.
 * Modifications are licensed under the License.
 ******************************************************************************/

package com.gsma.rcs.core.ims.security.cert;

import com.gsma.rcs.core.ims.ImsModule;
import com.gsma.rcs.platform.AndroidFactory;
import com.gsma.rcs.provider.settings.RcsSettings;
import com.gsma.rcs.provider.settings.RcsSettingsData;
import com.gsma.rcs.utils.CloseableUtils;
import com.gsma.rcs.utils.logger.Logger;

import com.telekom.bouncycastle.wrapper.SimpleContentSignerBuilder;

import local.org.bouncycastle.asn1.ASN1Encodable;
import local.org.bouncycastle.asn1.x500.X500Name;
import local.org.bouncycastle.asn1.x509.BasicConstraints;
import local.org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import local.org.bouncycastle.asn1.x509.GeneralName;
import local.org.bouncycastle.asn1.x509.GeneralNames;
import local.org.bouncycastle.asn1.x509.KeyPurposeId;
import local.org.bouncycastle.asn1.x509.KeyUsage;
import local.org.bouncycastle.asn1.x509.X509Extension;
import local.org.bouncycastle.cert.X509v3CertificateBuilder;
import local.org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import local.org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import local.org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import local.org.bouncycastle.operator.ContentSigner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * Keystore manager for certificates
 * 
 * @author B. JOGUET
 * @author Deutsche Telekom AG
 */
public class KeyStoreManager {
    /**
     * Rate to convert from seconds to milliseconds
     */
    private static final long SECONDS_TO_MILLISECONDS_CONVERSION_RATE = 1000;

    /**
     * Keystore name
     */
    private final static String KEYSTORE_NAME = "rcs_keystore.jks";

    // Changed by Deutsche Telekom
    /**
     * alias of own, client certificate
     */
    protected final static String CLIENT_CERT_ALIAS = "myJoynCertificate";

    // Changed by Deutsche Telekom
    /**
     * The logger
     */
    private static Logger sLogger = Logger.getLogger(KeyStoreManager.class.getName());

    /**
     * Keystore password
     */
    private final static String KEYSTORE_PASSWORD = "01RCSrcs";

    // Changed by Deutsche Telekom
    /**
     * The logger
     */
    private static String sFingerprint;

    // Changed by Deutsche Telekom
    /**
     * last used IP address
     */
    private static String sLastIpAddress;

    /**
     * Load the keystore manager
     * 
     * @param rcsSettings
     * @throws KeyStoreManagerException
     */
    // Changed by Deutsche Telekom
    public static void loadKeyStore(RcsSettings rcsSettings) throws KeyStoreManagerException {
        // Changed by Deutsche Telekom
        // List all registered providers for debug purpose
        if (sLogger.isActivated()) {
            Provider[] currentProviders = Security.getProviders();
            if (currentProviders.length > 0) {
                for (Provider provider : currentProviders) {
                    sLogger.debug("Registered provider: " + provider.getName() + "; info: "
                            + provider.getInfo());
                }
            }
        }

        // Create the keystore if not present
        // Changed by Deutsche Telekom
        if (!KeyStoreManager.isKeystoreExists()) {
            // Changed by Deutsche Telekom
            if (sLogger.isActivated()) {
                sLogger.debug("Create new keystore file " + getKeystorePath());
            }
            KeyStoreManager.createKeyStore();
        }

        // Add certificates if not present
        String certRoot = rcsSettings.getTlsCertificateRoot();
        if ((certRoot != null) && (certRoot.length() > 0)) {
            if (!KeyStoreManager.isCertificateEntry(buildCertificateAlias(certRoot))) {
                KeyStoreManager.addCertificates(certRoot);
            }
        }
        String certIntermediate = rcsSettings.getTlsCertificateIntermediate();
        if ((certIntermediate != null) && (certIntermediate.length() > 0)) {
            if (!KeyStoreManager.isCertificateEntry(buildCertificateAlias(certIntermediate))) {
                KeyStoreManager.addCertificates(certIntermediate);
            }
        }
    }

    /**
     * Returns the keystore type
     * 
     * @return Type
     */
    public static String getKeystoreType() {
        return KeyStore.getDefaultType();
    }

    /**
     * Returns the keystore password
     * 
     * @return Password
     */
    public static String getKeystorePassword() {
        return KEYSTORE_PASSWORD;
    }

    /**
     * Returns the keystore path
     * 
     * @return Keystore path
     */
    public static String getKeystorePath() {
        return AndroidFactory.getApplicationContext().getFilesDir().getAbsolutePath() + "/"
                + KEYSTORE_NAME;
    }

    // Changed by Deutsche Telekom
    /**
     * Returns the fingerprint of the client certificate
     * 
     * @return fingerprint
     */
    public static String getClientCertificateFingerprint() {
        return sFingerprint;
    }

    // Changed by Deutsche Telekom
    /**
     * Sets the fingerprint of the client certificate
     * 
     * @param cert certificate
     */
    public static void setClientCertificateFingerprint(Certificate cert) {
        sFingerprint = KeyStoreManager.getCertFingerprint(cert, "SHA-1");
    }

    /**
     * Test if a keystore is created
     * 
     * @return True if already created
     */
    // Changed by Deutsche Telekom
    private static boolean isKeystoreExists() {
        // Test file
        File file = new File(KeyStoreManager.getKeystorePath());
        if ((file == null) || (!file.exists()))
            return false;

        // Changed by Deutsche Telekom
        // Try to open the keystore
        KeyStore ks = KeyStoreManager.loadKeyStoreFromFile();
        if (ks != null) {
            ks = null;
            return true;
        } else {
            return false;
        }
    }

    // Changed by Deutsche Telekom
    /**
     * update (or create) current client certificate to reflect latest IP address
     * 
     * @param ipAddress IP address to be set in subjectAltName according to RFC 4572
     */
    public static void updateClientCertificate(final String ipAddress) {
        try {
            if (KeyStoreManager.isKeystoreExists()) {
                if (sLogger.isActivated()) {
                    sLogger.debug("Update client certificate");
                }

                // move processing to a worker thread as key generation is time
                // consuming
                Thread t = new Thread() {
                    /**
                     * Processing
                     */
                    public void run() {
                        createClientCertificate(ipAddress);
                    }
                };

                t.start();
            } else {
                if (sLogger.isActivated()) {
                    sLogger.debug("Client certificate not created as keystore file "
                            + getKeystorePath() + " is not available");
                }
            }
        } catch (Exception ex) {
            if (sLogger.isActivated()) {
                sLogger.error("Updating client certificate while checking keystore failed: ", ex);
            }
        }

    }

    // Changed by Deutsche Telekom
    private static synchronized void createClientCertificate(String ipAddress) {
        try {
            // IP address hasn't changed
            if (ipAddress != null && ipAddress.equals(sLastIpAddress)) {
                if (sLogger.isActivated()) {
                    sLogger.debug("IP address hasn't changed. No update needed.");
                }
                return;
            }
            // remember IP address for next update
            sLastIpAddress = ipAddress;

            // Load the keystore from file
            KeyStore ks = KeyStoreManager.loadKeyStoreFromFile();
            if (ks == null) {
                // loading keystore failed
                return;
            }

            // handle private and public key
            PrivateKey privKey = null;
            PublicKey pubKey = null;
            if (ks.isKeyEntry(CLIENT_CERT_ALIAS)) {
                // recycle key & certificate
                if (sLogger.isActivated()) {
                    sLogger.debug("old keypair is recycled");
                }

                PrivateKeyEntry entry = (PrivateKeyEntry) ks.getEntry(CLIENT_CERT_ALIAS,
                        new KeyStore.PasswordProtection(KEYSTORE_PASSWORD.toCharArray()));
                privKey = entry.getPrivateKey();
                pubKey = entry.getCertificate().getPublicKey();
                ks.deleteEntry(CLIENT_CERT_ALIAS);
            } else {
                // generate key pair to be used by the certificate
                if (sLogger.isActivated()) {
                    sLogger.debug("new keypair is generated");
                }

                KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
                SecureRandom secureRandom = new SecureRandom();
                // Do *not* seed secureRandom! Automatically seeded
                // from system entropy.
                keyGen.initialize(1024, secureRandom);
                KeyPair keypair = keyGen.generateKeyPair();
                privKey = keypair.getPrivate();
                pubKey = keypair.getPublic();
            }

            // generate a new X.509 certificate
            X509Certificate[] certChain = new X509Certificate[1];
            // X500Name subjectName = new X500Name("CN="
            // + OemCustomization.customizeString("com.gsma.rcs.client"));
            X500Name subjectName = new X500Name("CN=com.gsma.rcs.client");
            long timestamp = System.currentTimeMillis();
            Date startDate = new Date(timestamp - 24 * 60 * 60
                    * SECONDS_TO_MILLISECONDS_CONVERSION_RATE);
            // validity of 1 year
            Date endDate = new Date(timestamp + 365L * 26 * 60 * 60
                    * SECONDS_TO_MILLISECONDS_CONVERSION_RATE);
            X509v3CertificateBuilder certGen = new JcaX509v3CertificateBuilder(subjectName,
                    BigInteger.ONE, startDate, endDate, subjectName, pubKey);
            JcaX509ExtensionUtils x509ExtUtils = new JcaX509ExtensionUtils();
            certGen.addExtension(X509Extension.subjectKeyIdentifier, false,
                    (ASN1Encodable) x509ExtUtils.createSubjectKeyIdentifier(pubKey));
            certGen.addExtension(X509Extension.authorityKeyIdentifier, false,
                    (ASN1Encodable) x509ExtUtils.createAuthorityKeyIdentifier(pubKey));
            certGen.addExtension(X509Extension.keyUsage, false, new KeyUsage(
                    KeyUsage.digitalSignature | KeyUsage.keyCertSign));
            certGen.addExtension(X509Extension.extendedKeyUsage, false, new ExtendedKeyUsage(
                    KeyPurposeId.id_kp_clientAuth));
            // set subjectAltName to IP address and SIP URI
            certGen.addExtension(X509Extension.subjectAlternativeName, false, new GeneralNames(
                    new GeneralName[] {
                            new GeneralName(GeneralName.iPAddress, ipAddress),
                            new GeneralName(GeneralName.uniformResourceIdentifier,
                                    ImsModule.IMS_USER_PROFILE.getPublicUri())
                    }));
            // set basicConstraints to CA
            certGen.addExtension(X509Extension.basicConstraints, false, new BasicConstraints(true));

            // self-sign certificate
            ContentSigner sigGen = new SimpleContentSignerBuilder().build(privKey);

            JcaX509CertificateConverter certConv = new JcaX509CertificateConverter();
            certChain[0] = certConv.getCertificate(certGen.build(sigGen));

            // store fingerprint for further use
            KeyStoreManager.setClientCertificateFingerprint(certChain[0]);

            // place new key (incl. certificate) into keystore
            ks.setEntry(CLIENT_CERT_ALIAS, new KeyStore.PrivateKeyEntry(privKey, certChain),
                    new KeyStore.PasswordProtection(KEYSTORE_PASSWORD.toCharArray()));

            // Save the keystore to file
            KeyStoreManager.saveKeyStoreToFile(ks);

            if (sLogger.isActivated()) {
                sLogger.debug("Client certificate " + CLIENT_CERT_ALIAS + " for IP address "
                        + ipAddress + " with fingerprint "
                        + KeyStoreManager.getClientCertificateFingerprint() + " added");
            }
        } catch (Exception ex) {
            // TODO: check whether Exception specific code is
            // useful
            // KeyStoreManagerException, FileNotFoundException,
            // NoSuchAlgorithmException, KeyStoreException,
            // CertificateException, IOException,
            // OperatorCreationException
            if (sLogger.isActivated()) {
                sLogger.error("Creating client certificate failed: ", ex);
            }
        }
    }

    /**
     * Create the RCS keystore
     * 
     * @throws KeyStoreManagerException
     */
    private static void createKeyStore() throws KeyStoreManagerException {
        File file = new File(getKeystorePath());
        if ((file == null) || (!file.exists())) {
            try {
                // Build empty keystore
                KeyStore ks = KeyStore.getInstance(getKeystoreType());
                // Changed by Deutsche Telekom
                synchronized (KeyStoreManager.class) {
                    ks.load(null, KEYSTORE_PASSWORD.toCharArray());
                }

                // Export keystore in a file
                // Changed by Deutsche Telekom
                KeyStoreManager.saveKeyStoreToFile(ks);
            } catch (Exception e) {
                throw new KeyStoreManagerException(e.getMessage());
            }
        }
    }

    /**
     * Check if a certificate is in the keystore
     * 
     * @param alias certificate alias
     * @return True if available
     */
    // Changed by Deutsche Telekom
    private static boolean isCertificateEntry(String alias) {
        boolean result = false;
        try {
            // Open the existing keystore
            KeyStore ks = KeyStoreManager.loadKeyStoreFromFile();
            if (ks == null) {
                // loading the keystore failed
                return result;
            }

            // Changed by Deutsche Telekom
            result = ks.isCertificateEntry(alias);
            ks = null;
        } catch (Exception ex) {
            // Changed by Deutsche Telekom
            if (sLogger.isActivated()) {
                sLogger.error("Checking key " + alias + " failed: ", ex);
            }
        }
        return result;
    }

    /**
     * Add a certificate or all certificates in folder in the keystore
     * 
     * @param path certificates path
     */
    // Changed by Deutsche Telekom
    private static void addCertificates(String path) {
        try {
            // Open the existing keystore
            // Changed by Deutsche Telekom
            KeyStore ks = KeyStoreManager.loadKeyStoreFromFile();
            if (ks == null) {
                // loading the keystore failed
                return;
            }

            // Open certificates path
            File pathFile = new File(path);
            if (pathFile.isDirectory()) {
                // The path is a folder, add all certificates
                File[] certificates = pathFile.listFiles(new FilenameFilter() {
                    public boolean accept(File dir, String filename) {
                        return filename.endsWith(RcsSettingsData.CERTIFICATE_FILE_TYPE);
                    }
                });

                if (certificates != null) {
                    for (File file : certificates) {
                        // Get certificate and add in keystore
                        CertificateFactory cf = CertificateFactory.getInstance("X.509");
                        InputStream inStream = new FileInputStream(file);
                        X509Certificate cert = (X509Certificate) cf.generateCertificate(inStream);
                        inStream.close();
                        ks.setCertificateEntry(buildCertificateAlias(path), cert);

                        // Save the keystore
                        // Changed by Deutsche Telekom
                        KeyStoreManager.saveKeyStoreToFile(ks);
                    }
                }
            } else {
                // The path is a file, add certificate
                if (path.endsWith(RcsSettingsData.CERTIFICATE_FILE_TYPE)) {
                    // Get certificate and add in keystore
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    InputStream inStream = new FileInputStream(path);
                    X509Certificate cert = (X509Certificate) cf.generateCertificate(inStream);
                    inStream.close();
                    ks.setCertificateEntry(buildCertificateAlias(path), cert);

                    // Save the keystore
                    // Changed by Deutsche Telekom
                    KeyStoreManager.saveKeyStoreToFile(ks);
                }
            }
        } catch (Exception e) {
            if (sLogger.isActivated()) {
                sLogger.error("adding certificate " + path + " failed: ", e);
            }
        }
    }

    /**
     * Build alias from path
     * 
     * @param path File path
     * @return Alias
     */
    private static String buildCertificateAlias(String path) {
        String alias = "";
        File file = new File(path);
        String filename = file.getName();
        long lastModified = file.lastModified();
        int lastDotPosition = filename.lastIndexOf('.');
        if (lastDotPosition > 0)
            alias = filename.substring(0, lastDotPosition) + lastModified;
        else
            alias = filename + lastModified;
        return alias;
    }

    /**
     * Returns the fingerprint of a certificate
     * 
     * @param cert Certificate
     * @param algorithm hash algorithm to be used
     * @return String as xx:yy:zz
     */
    // Changed by Deutsche Telekom
    public static String getCertFingerprint(Certificate cert, String algorithm) {
        try {
            // Changed by Deutsche Telekom
            if (cert != null) {
                if (sLogger.isActivated()) {
                    sLogger.debug("Getting " + algorithm + " fingerprint for certificate: "
                            + cert.toString());
                }
                MessageDigest md = MessageDigest.getInstance(algorithm);
                byte[] der = cert.getEncoded();
                md.update(der);
                byte[] digest = md.digest();
                return hexify(digest);
            } else {
                return null;
            }
        } catch (Exception e) {
            // Changed by Deutsche Telekom
            if (sLogger.isActivated()) {
                sLogger.error("getCertFingerprint failed: ", e);
            }
            return null;
        }
    }

    /**
     * Hexify a byte array
     * 
     * @param bytes Byte array
     * @return String
     */
    private static String hexify(byte bytes[]) {
        char[] hexDigits = {
                '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
        };
        StringBuffer buf = new StringBuffer(bytes.length * 2);
        for (int i = 0; i < bytes.length; ++i) {
            if (i != 0) {
                buf.append(":");
            }
            buf.append(hexDigits[(bytes[i] & 0xf0) >> 4]);
            buf.append(hexDigits[bytes[i] & 0x0f]);
        }
        return buf.toString();
    }

    // Changed by Deutsche Telekom
    /**
     * Returns whether own certificates are used
     * 
     * @param rcsSettings
     * @return True if own certificates are used
     */
    public static boolean isOwnCertificateUsed(RcsSettings rcsSettings) {
        try {
            String certRoot = rcsSettings.getTlsCertificateRoot();
            if ((certRoot != null) && (certRoot.length() > 0)) {
                // Changed by Deutsche Telekom
                return KeyStoreManager.isCertificateEntry(buildCertificateAlias(certRoot));
            } else {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    // Changed by Deutsche Telekom
    /**
     * Returns keystore from file
     * 
     * @return KeyStore
     */
    private static KeyStore loadKeyStoreFromFile() {
        FileInputStream fis = null;
        KeyStore ks = null;
        File file = null;
        try {
            // Test file
            file = new File(KeyStoreManager.getKeystorePath());
            if ((file != null) && (file.exists())) {
                fis = new FileInputStream(getKeystorePath());

                // Open the existing keystore
                ks = KeyStore.getInstance(getKeystoreType());
                synchronized (KeyStoreManager.class) {
                    ks.load(fis, KEYSTORE_PASSWORD.toCharArray());
                }
            }
        } catch (Exception ex) {
            if (sLogger.isActivated()) {
                sLogger.error("Loading " + getKeystorePath() + " of type " + getKeystoreType()
                        + " failed: ", ex);
            }
            if ((file != null) && (file.exists())) {
                // delete eronous file
                file.delete();
            }
            return null;
        } finally {
            CloseableUtils.close(fis);
        }
        return ks;
    }

    // Changed by Deutsche Telekom
    /**
     * Saves keystore in file
     */
    private static void saveKeyStoreToFile(KeyStore ks) {
        if (ks == null) {
            return;
        }

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(getKeystorePath());

            // Save the existing keystore
            synchronized (KeyStoreManager.class) {
                ks.store(fos, KEYSTORE_PASSWORD.toCharArray());
            }
        } catch (Exception ex) {
            if (sLogger.isActivated()) {
                sLogger.error("Saving " + getKeystorePath() + " of type " + getKeystoreType()
                        + " failed: ", ex);
            }
        } finally {
            CloseableUtils.close(fos);
        }
    }
}
