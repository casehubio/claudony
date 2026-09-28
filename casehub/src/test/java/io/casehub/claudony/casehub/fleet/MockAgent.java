package io.casehub.claudony.casehub.fleet;

import java.util.Scanner;

public class MockAgent {
    public static void main(String[] args) {
        System.out.println("MOCK_AGENT_READY");
        System.out.flush();
        var scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            System.out.println("ECHO:" + line);
            System.out.flush();
        }
    }
}
