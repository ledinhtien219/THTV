package com.github.deadknight.aaproxycompanion.plugin.aidl;

interface IAAProxyPlugin {
    int getApiVersion();
    String getManifestJson();
    byte[] render(String renderRequestJson);
    byte[] onAction(String actionJson, String renderRequestJson);
}
