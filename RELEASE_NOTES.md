New
- Admin is only for you now: the Admin panel is hidden from the menu. Open Settings and tap the version 7 times ("You are 3 steps away…") to bring it back
- Opening Admin asks for your password. The server checks it, and it stays signed in on your phone for 30 days
- Set the password yourself in GitHub: repo → Settings → Secrets and variables → Actions → New repository secret, name ADMIN_PASSWORD. It goes live within about 10 minutes, no new APK needed
- "Lock admin" (the lock at the top of Admin) signs out on this phone and hides Admin again
- The web Admin panel also asks for the password now

Fixed
- The admin key is out of the app. The key inside the APK can only sync contacts and photos; it can't search or see anything
- 5 wrong passwords lock sign-in for 15 minutes (longer if it keeps happening)
- Changing the password in GitHub signs out every phone that was signed in
- Older versions (247–250) keep syncing contacts but can no longer search
