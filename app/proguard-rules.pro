# R8（minifyEnabled true，Play App optimization 要求）。规则按「谁按名字
# 找代码」来保：JNI 按符号名绑定、WebView 按注解反射取方法。

# JNI 名称绑定：native 方法经 Java_pkg_Class_method 符号名绑定
# （librime/smoke 引擎、sherpa-onnx）。类名或方法名被混淆即
# UnsatisfiedLinkError。
-keepclasseswithmembernames class * {
    native <methods>;
}

# WebView JS bridge：keyboard.js / 设置页经 addJavascriptInterface 调
# FeelimeNative / SettingsBridge，注解反射查找成员。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# sherpa-onnx AAR：JNI 直呼其数据类（SupplementalContext 之类按字段名
# 取值），保守全保。
-keep class com.k2fsa.sherpa.onnx.** { *; }

# commons-compress 的 zstd 分支是可选依赖（R8 生成的 missing_rules），
# 模型 zip 走 deflate，不用 zstd。
-dontwarn com.github.luben.zstd.ZstdInputStream
