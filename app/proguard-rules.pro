# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep,allowobfuscation,allowshrinking interface com.example.githubdesktop.data.api.GitHubApi

# Gson binds the API models reflectively from @SerializedName.
-keep class com.example.githubdesktop.data.model.** { *; }