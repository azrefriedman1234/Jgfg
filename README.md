# פסיפלונט מובייל (Pasiflonet Mobile)

אפליקציית אנדרואיד (Kotlin + Gradle) שמתחברת לטלגרם דרך **TDLib** ומציגה בלייב הודעות מערוצים (חדשות למעלה),
עם חלון פרטים שמאפשר:
- עריכת טקסט
- תרגום אוטומטי (ML Kit – חינמי, ללא API)
- סימון מיקום סימן מים בנגיעה
- סימון אזורי טשטוש (ציור מלבן עם גרירה)
- שליחה לערוץ יעד ברקע (WorkManager) + Toast עם ID

**AARים** יורדים אוטומטית בזמן Build:
- TDLib: https://jitpack.io/com/github/tdlibx/td/1.8.56/td-1.8.56.aar
- FFmpegKit: https://artifactory.appodeal.com/appodeal-public/com/arthenica/ffmpeg-kit-full-gpl/6.0-2.LTS/ffmpeg-kit-full-gpl-6.0-2.LTS.aar

---

## איך מקמפלים בלי Android Studio (Termux + GitHub Actions)

### 1) Termux – יצירת ריפו והעלאה ל-GitHub
```bash
pkg update -y
pkg install -y git
git config --global user.name "YOUR_NAME"
git config --global user.email "YOUR_EMAIL"

# צור תיקייה
mkdir -p ~/pasiflonet_mobile && cd ~/pasiflonet_mobile

# אם כבר הורדת ZIP – חלץ אותו כאן.
# אחרת: תיצור ריפו חדש ב-GitHub (דרך האפליקציה/דפדפן), ושים כאן את הקבצים.

git init
git add .
git commit -m "Initial Pasiflonet Mobile"
git branch -M main
git remote add origin https://github.com/<YOUR_GITHUB>/<YOUR_REPO>.git
git push -u origin main
```

### 2) GitHub Actions
ברגע שהקוד ב-GitHub – ה-Workflow ירוץ לבד ויבנה APK דיבאג.

הורדה:
- GitHub → Actions → בחר את ה-Run האחרון → Artifacts → `app-debug.apk`

---

## הגדרות בתוך האפליקציה
במסך התחברות:
- בחר סימן מים מהגלריה
- מלא API ID, API HASH, מספר טלפון
- שלח קוד, הזן קוד (ואם צריך 2FA)
- הגדר **Target Chat ID** (ID מספרי של ערוץ היעד)

---

## הערות חשובות
- שדה **Target Chat ID** הוא מספר (Long). לדוגמה ערוץ/סופרגרופ ב-TDLib לרוב יהיה מזהה שלילי.
- הטשטוש הוא “חזק” באמצעות FFmpeg boxblur על אזורים שסימנת.
- אם אין מדיה בהודעה – השליחה תיפול לטקסט בלבד.
