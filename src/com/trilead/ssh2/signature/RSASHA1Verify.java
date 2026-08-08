
package com.trilead.ssh2.signature;

import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.trilead.ssh2.crypto.SimpleDERReader;
import com.trilead.ssh2.crypto.digest.SHA1;
import com.trilead.ssh2.log.Logger;
import com.trilead.ssh2.packets.TypesReader;
import com.trilead.ssh2.packets.TypesWriter;


/**
 * RSASHA1Verify.
 * 
 * @author Christian Plattner, plattner@trilead.com
 * @version $Id: RSASHA1Verify.java,v 1.1 2007/10/15 12:49:57 cplattne Exp $
 */
public class RSASHA1Verify
{
	private static final Logger log = Logger.getLogger(RSASHA1Verify.class);

	public static RSAPublicKey decodeSSHRSAPublicKey(byte[] key) throws IOException
	{
		TypesReader tr = new TypesReader(key);

		String key_format = tr.readString();

		if (key_format.equals("ssh-rsa") == false)
			throw new IllegalArgumentException("This is not a ssh-rsa public key");

		BigInteger e = tr.readMPINT();
		BigInteger n = tr.readMPINT();

		if (tr.remain() != 0)
			throw new IOException("Padding in RSA public key!");

		return new RSAPublicKey(e, n);
	}

	public static byte[] encodeSSHRSAPublicKey(RSAPublicKey pk) throws IOException
	{
		TypesWriter tw = new TypesWriter();

		tw.writeString("ssh-rsa");
		tw.writeMPInt(pk.getE());
		tw.writeMPInt(pk.getN());

		return tw.getBytes();
	}

	public static String getSignatureFormat(byte[] sig) throws IOException
	{
		TypesReader tr = new TypesReader(sig);
		String sig_format = tr.readString();

		if ((sig_format.equals("ssh-rsa") == false) && (sig_format.equals("rsa-sha2-256") == false)
				&& (sig_format.equals("rsa-sha2-512") == false))
			throw new IOException("Peer sent wrong signature format");

		return sig_format;
	}

	public static RSASignature decodeSSHRSASignature(byte[] sig) throws IOException
	{
		String sig_format = getSignatureFormat(sig);
		TypesReader tr = new TypesReader(sig);
		tr.readString();

		/* S is NOT an MPINT. "The value for 'rsa_signature_blob' is encoded as a string
		 * containing s (which is an integer, without lengths or padding, unsigned and in
		 * network byte order)." See also below.
		 */

		byte[] s = tr.readByteString();

		if (s.length == 0)
			throw new IOException("Error in RSA signature, S is empty.");

		if (log.isEnabled())
		{
			log.log(80, "Decoding ssh-rsa signature string (length: " + s.length + ")");
		}

		if (tr.remain() != 0)
			throw new IOException("Padding in RSA signature!");

		return new RSASignature(new BigInteger(1, s));
	}

	public static byte[] encodeSSHRSASignature(RSASignature sig) throws IOException
	{
		return encodeSSHRSASignature(sig, "ssh-rsa");
	}

	public static byte[] encodeSSHRSASignature(RSASignature sig, String sigFormat) throws IOException
	{
		TypesWriter tw = new TypesWriter();

		tw.writeString(sigFormat);

		/* S is NOT an MPINT. "The value for 'rsa_signature_blob' is encoded as a string
		 * containing s (which is an integer, without lengths or padding, unsigned and in
		 * network byte order)."
		 */

		byte[] s = sig.getS().toByteArray();

		/* Remove first zero sign byte, if present */

		if ((s.length > 1) && (s[0] == 0x00))
			tw.writeString(s, 1, s.length - 1);
		else
			tw.writeString(s, 0, s.length);

		return tw.getBytes();
	}

	private static final byte[] SHA1_DER_HEADER = new byte[] {
		0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00,
		0x04, 0x14 };
	private static final byte[] SHA256_DER_HEADER = new byte[] {
		0x30, 0x31, 0x30, 0x0d, 0x06, 0x09, 0x60, (byte) 0x86, 0x48, 0x01, 0x65, 0x03,
		0x04, 0x02, 0x01, 0x05, 0x00, 0x04, 0x20 };
	private static final byte[] SHA512_DER_HEADER = new byte[] {
		0x30, 0x51, 0x30, 0x0d, 0x06, 0x09, 0x60, (byte) 0x86, 0x48, 0x01, 0x65, 0x03,
		0x04, 0x02, 0x03, 0x05, 0x00, 0x04, 0x40 };

	public static RSASignature generateSignature(byte[] message, RSAPrivateKey pk) throws IOException
	{
		return generateSignature(message, pk, "SHA-1");
	}

	public static RSASignature generateSignature(byte[] message, RSAPrivateKey pk, String digestType)
			throws IOException
	{
		byte[] digest;
		byte[] der_header;

		try {
			MessageDigest md = MessageDigest.getInstance(digestType);
			md.update(message);
			digest = md.digest();
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("Unable to calculate RSA signature hash using " + digestType, e);
		}

		if ("SHA-256".equals(digestType) || "SHA256".equals(digestType)) {
			der_header = SHA256_DER_HEADER;
		} else if ("SHA-512".equals(digestType) || "SHA512".equals(digestType)) {
			der_header = SHA512_DER_HEADER;
		} else {
			der_header = SHA1_DER_HEADER;
		}

		int rsa_block_len = (pk.getN().bitLength() + 7) / 8;

		int num_pad = rsa_block_len - (2 + der_header.length + digest.length) - 1;

		if (num_pad < 8)
			throw new IOException("Cannot sign with RSA, message too long");

		byte[] sig = new byte[der_header.length + digest.length + 2 + num_pad];

		sig[0] = 0x01;

		for (int i = 0; i < num_pad; i++)
		{
			sig[i + 1] = (byte) 0xff;
		}

		sig[num_pad + 1] = 0x00;

		System.arraycopy(der_header, 0, sig, 2 + num_pad, der_header.length);
		System.arraycopy(digest, 0, sig, 2 + num_pad + der_header.length, digest.length);

		BigInteger m = new BigInteger(1, sig);

		BigInteger s = m.modPow(pk.getD(), pk.getN());

		return new RSASignature(s);
	}

	public static boolean verifySignature(byte[] message, RSASignature ds, RSAPublicKey dpk) throws IOException
	{
		return verifySignature(message, ds, dpk, "SHA-1");
	}

	public static boolean verifySignature(byte[] message, RSASignature ds, RSAPublicKey dpk, String digestType)
			throws IOException
	{
		String jcaSignatureName;

		if ("SHA-256".equals(digestType) || "SHA256".equals(digestType))
			jcaSignatureName = "SHA256withRSA";
		else if ("SHA-512".equals(digestType) || "SHA512".equals(digestType))
			jcaSignatureName = "SHA512withRSA";
		else
			jcaSignatureName = "SHA1withRSA";

		try
		{
			BigInteger n = dpk.getN();
			BigInteger e = dpk.getE();
			BigInteger s = ds.getS();

			if (n.compareTo(s) <= 0)
			{
				log.log(20, "ssh-rsa signature: n.compareTo(s) <= 0");
				return false;
			}

			int rsa_block_len = (n.bitLength() + 7) / 8;
			if (rsa_block_len < 1)
			{
				log.log(20, "ssh-rsa signature: rsa_block_len < 1");
				return false;
			}

			byte[] sBytes = s.toByteArray();
			byte[] signatureBytes = new byte[rsa_block_len];
			int offset = 0;

			if ((sBytes.length > 0) && (sBytes[0] == 0x00))
				offset++;

			int copyLen = Math.min(sBytes.length - offset, rsa_block_len);
			System.arraycopy(sBytes, offset, signatureBytes, rsa_block_len - copyLen, copyLen);

			java.security.interfaces.RSAPublicKey jcaPublicKey = (java.security.interfaces.RSAPublicKey) java.security.KeyFactory
					.getInstance("RSA").generatePublic(new java.security.spec.RSAPublicKeySpec(n, e));
			java.security.Signature signature = java.security.Signature.getInstance(jcaSignatureName);
			signature.initVerify(jcaPublicKey);
			signature.update(message);

			return signature.verify(signatureBytes);
		}
		catch (Exception e)
		{
			throw new IOException("Unable to verify RSA signature using " + digestType, e);
		}
	}
}
