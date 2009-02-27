package org.bouncycastle.cms;

import org.bouncycastle.asn1.ASN1Object;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.cms.IssuerAndSerialNumber;
import org.bouncycastle.asn1.cms.KeyAgreeRecipientInfo;
import org.bouncycastle.asn1.cms.OriginatorPublicKey;
import org.bouncycastle.asn1.cms.RecipientEncryptedKey;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.NoSuchPaddingException;

/**
 * the RecipientInfo class for a recipient who has been sent a message
 * encrypted using key agreement.
 */
public class KeyAgreeRecipientInformation
    extends RecipientInformation
{
    private KeyAgreeRecipientInfo info;
    private ASN1OctetString       _encryptedKey;

    public KeyAgreeRecipientInformation(
        KeyAgreeRecipientInfo info,
        AlgorithmIdentifier   encAlg,
        InputStream data)
    {
        this(info, encAlg, null, data);
    }

    public KeyAgreeRecipientInformation(
        KeyAgreeRecipientInfo info,
        AlgorithmIdentifier   encAlg,
        AlgorithmIdentifier   macAlg,
        InputStream data)
    {
        super(encAlg, macAlg, AlgorithmIdentifier.getInstance(info.getKeyEncryptionAlgorithm()), data);

        this.info = info;

        try
        {
            ASN1Sequence s = this.info.getRecipientEncryptedKeys();
            RecipientEncryptedKey id = RecipientEncryptedKey.getInstance(s.getObjectAt(0));

            IssuerAndSerialNumber iAnds = id.getIdentifier().getIssuerAndSerialNumber();

            rid = new RecipientId();
            rid.setIssuer(iAnds.getName().getEncoded());
            rid.setSerialNumber(iAnds.getSerialNumber().getValue());

            _encryptedKey = id.getEncryptedKey();
        }
        catch (IOException e)
        {
            throw new IllegalArgumentException("invalid rid in KeyAgreeRecipientInformation");
        }
    }

    protected Key unwrapKey(Key key, Provider prov)
        throws CMSException
    {
        try
        {
            OriginatorPublicKey origK = info.getOriginator().getOriginatorKey();
            PrivateKeyInfo privInfo = PrivateKeyInfo.getInstance(ASN1Object.fromByteArray(key.getEncoded()));
            SubjectPublicKeyInfo pubInfo = new SubjectPublicKeyInfo(privInfo.getAlgorithmId(), origK.getPublicKey().getBytes());
            X509EncodedKeySpec pubSpec = new X509EncodedKeySpec(pubInfo.getEncoded());
            KeyFactory fact = KeyFactory.getInstance(keyEncAlg.getObjectId().getId(), prov);
            KeyAgreement agreement = KeyAgreement.getInstance(keyEncAlg.getObjectId().getId(), prov);

            agreement.init(key);

            agreement.doPhase(fact.generatePublic(pubSpec), true);

            String wrapAlg = DERObjectIdentifier.getInstance(
                                   ASN1Sequence.getInstance(keyEncAlg.getParameters()).getObjectAt(0)).getId();

            Key wKey = agreement.generateSecret(wrapAlg);

            Cipher keyCipher = Cipher.getInstance(wrapAlg, prov);

            keyCipher.init(Cipher.UNWRAP_MODE, wKey);

            AlgorithmIdentifier aid = encAlg;
            if (aid == null)
            {
                aid = macAlg;
            }
            
            String              alg = aid.getObjectId().getId();

            byte[]              encryptedKey = _encryptedKey.getOctets();

            return keyCipher.unwrap(encryptedKey, alg, Cipher.SECRET_KEY);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new CMSException("can't find algorithm.", e);
        }
        catch (InvalidKeyException e)
        {
            throw new CMSException("key invalid in message.", e);
        }
        catch (InvalidKeySpecException e)
        {
            throw new CMSException("originator key spec invalid.", e);
        }
        catch (NoSuchPaddingException e)
        {
            throw new CMSException("required padding not supported.", e);
        }
        catch (Exception e)
        {
            throw new CMSException("originator key invalid.", e);
        }
    }
    /**
     * decrypt the content and return it
     */
    public CMSTypedStream getContentStream(
        Key key,
        String prov)
        throws CMSException, NoSuchProviderException
    {
        return getContentStream(key, CMSUtils.getProvider(prov));
    }

    public CMSTypedStream getContentStream(
        Key key,
        Provider prov)
        throws CMSException
    {
        Key sKey = unwrapKey(key, prov);

        return getContentFromSessionKey(sKey, prov);
    }
}
