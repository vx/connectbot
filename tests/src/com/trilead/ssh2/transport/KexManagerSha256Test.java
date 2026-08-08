package com.trilead.ssh2.transport;

public class KexManagerSha256Test {
    public static void main(String[] args) {
        String[] algos = KexManager.getDefaultKexAlgorithmList();
        for (String algo : algos) {
            if ("diffie-hellman-group14-sha256".equals(algo)) {
                System.out.println("PASS: modern kex algorithm present");
                return;
            }
        }

        throw new AssertionError("diffie-hellman-group14-sha256 not present in default kex list");
    }
}
