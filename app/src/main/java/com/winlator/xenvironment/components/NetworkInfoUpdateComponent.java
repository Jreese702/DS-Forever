package com.winlator.xenvironment.components;

import android.content.Context;
import android.util.Log;

import com.winlator.core.FileUtils;
import com.winlator.core.NetworkHelper;
import com.winlator.xenvironment.EnvironmentComponent;

import java.io.File;
import java.util.List;

public class NetworkInfoUpdateComponent extends EnvironmentComponent {

    @Override
    public void start() {
        Log.d("NetworkInfoUpdateComponent", "Starting...");
        Context context = environment.getContext();
        NetworkHelper networkHelper = new NetworkHelper(context);
        updateIFAddrsFile(networkHelper.getIFAddresses());
        updateEtcHostsFile(networkHelper.getIPv4Address());
    }

    @Override
    public void stop() {
        Log.d("NetworkInfoUpdateComponent", "Stopping...");
    }

    public void updateIFAddrsFile(List<NetworkHelper.IFAddress> ifAddresses) {
        File file = new File(environment.getImageFs().getTmpDir(), "ifaddrs");
        String content = "";
        if (!ifAddresses.isEmpty()) {
            for (NetworkHelper.IFAddress ifAddress : ifAddresses) {
                StringBuilder sb = new StringBuilder();
                sb.append(content);
                sb.append(!content.isEmpty() ? "\n" : "");
                sb.append(ifAddress.toString());
                content = sb.toString();
            }
        } else {
            content = new NetworkHelper.IFAddress().toString();
        }
        FileUtils.writeString(file, content);
    }

    public void updateEtcHostsFile(String ipAddress) {
        String ip = ipAddress != null ? ipAddress : "127.0.0.1";
        File file = new File(environment.getImageFs().getRootDir(), "etc/hosts");
        FileUtils.writeString(file, ip + "\tlocalhost\n");
    }
}
