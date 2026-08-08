package com.trilead.ssh2.transport;

public class KexManagerHostKeyAlgorithmsTest {
    public static void main(String[] args) {
        String[] algos = KexManager.getDefaultServerHostkeyAlgorithmList();
        boolean hasSha2 = false;
        for (String algo : algos) {
            if ("rsa-sha2-512".equals(algo) || "rsa-sha2-256".equals(algo)) {
                hasSha2 = true;
                break;
            }
        }

        if (!hasSha2) {
            throw new AssertionError("modern RSA host key algorithms were not advertised");
        }

        System.out.println("PASS: modern RSA host key algorithms present");
    }
}
