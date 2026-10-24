GoFox - বিল্ড করার নিয়ম
1) GitHub এ নতুন Repository বানাও (যেমন GoFox)।
2) এই zip এর সব ফাইল ও ফোল্ডার (app, .github, build.gradle, settings.gradle, gradle.properties) রিপোতে আপলোড করো।
   - .github/workflows/build.yml ফাইলটি অবশ্যই থাকতে হবে। আপলোডে না এলে GitHub এ
     Add file > Create new file চেপে নাম দাও: .github/workflows/build.yml এবং ফাইলের লেখা পেস্ট করো।
3) আপলোড (push) হলে Actions ট্যাবে বিল্ড নিজে চালু হবে (বা Run workflow চাপো)।
4) বিল্ড শেষ হলে ওই রানের নিচে Artifacts এ GoFox-APK ডাউনলোড করো, zip খুলে GoFox.apk ইন্সটল করো।
5) বিল্ড ফেল করলে লগের লাল লাইনগুলো আমাকে পাঠাও, আমি ঠিক করে দেব।
