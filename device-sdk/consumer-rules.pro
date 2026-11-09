# The Agent's binder interfaces and the JCA provider's classes are looked up by name.
-keep class net.ohrtech.device.aidl.** { *; }
-keep class net.ohrtech.device.keys.** { *; }
# App Startup finds the initializer by its name (manifest meta-data).
-keep class net.ohrtech.device.OhrDeviceSdkInitializer { <init>(); }
