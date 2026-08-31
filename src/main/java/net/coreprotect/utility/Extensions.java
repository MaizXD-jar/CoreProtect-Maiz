package net.coreprotect.utility;

import java.lang.reflect.Method;

public class Extensions {

    public static void startBackgroundService() {
        invokeBackgroundService("start");
    }

    public static void stopBackgroundService() {
        invokeBackgroundService("stop");
    }

    private static void invokeBackgroundService(String methodName) {
        try {
            Class<?> serviceClass = Class.forName("net.coreprotect.utility.extensions.BackgroundService");
            Method serviceMethod = serviceClass.getDeclaredMethod(methodName);
            serviceMethod.invoke(null);
        }
        catch (ClassNotFoundException e) {
            // plugin not compiled with extension
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

}
