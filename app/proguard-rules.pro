# R8 混淆规则。
#
# isMinifyEnabled = true 之后这个文件仍然基本是空的，理由（与 build.gradle.kts
# 里的注释对应）：
#   · 全项目没有反射调用，R8 的静态分析够用
#   · JSON 走系统自带的 org.json，平台类不会被裁
#   · ViewBinding 是编译期直接引用，无需 keep
#   · Activity / Receiver 等四大组件的 keep 规则由 AGP 按 Manifest 自动生成
#
# 将来如果引入任何「按字符串名字找类」的东西（反射、序列化框架、
# WebView JS 桥……），必须在这里补 keep 规则 ——
# R8 的坑从来不是「编译不过」，而是「运行时才崩」。
