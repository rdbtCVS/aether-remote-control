package com.yario.aetherremote;

record AetherClientInstance(String id, String accountName, int port, long updatedAt) {
    String controlUrl(String command) {
        return "http://127.0.0.1:" + port + "/aether-control?command=" + command;
    }

    String controlUrl(String command, String query) {
        return "http://127.0.0.1:" + port + "/aether-control?command=" + command + "&" + query;
    }
}
