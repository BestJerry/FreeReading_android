# Readium and Media3 supply their own consumer rules.

# PDFBox 只用于提取文字。JPEG 2000 解码器是可选依赖，页面由系统 PdfRenderer 渲染。
-dontwarn com.gemalto.jp2.JP2Decoder
