package com.trilead.ssh2.crypto.digest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 digest wrapper for the trilead SSH2 code.
 */
public final class SHA256 implements Digest
{
	private final MessageDigest md;

	public SHA256()
	{
		try
		{
			md = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new RuntimeException(e);
		}
	}

	public int getDigestLength()
	{
		return md.getDigestLength();
	}

	public void update(byte b)
	{
		md.update(b);
	}

	public void update(byte[] b)
	{
		md.update(b);
	}

	public void update(byte[] b, int off, int len)
	{
		md.update(b, off, len);
	}

	public void reset()
	{
		md.reset();
	}

	public void digest(byte[] out)
	{
		digest(out, 0);
	}

	public void digest(byte[] out, int off)
	{
		byte[] digest = md.digest();
		System.arraycopy(digest, 0, out, off, digest.length);
	}
}
