# Keep the two Radar accounts across reinstalls

The note history and pairing approvals are stored under Firebase Authentication
UIDs. A reinstall removes an anonymous sign-in stored on the phone, but an
email/password sign-in can recover the same UID. The one-time script below adds
credentials to the two *existing* users, preserving their notes and approvals.

1. In Firebase Console for `widget-33ff3`, open Authentication → Sign-in method
   and enable **Email/Password**.
2. In Google Cloud Shell, use the existing `~/note-migration` folder where
   `firebase-admin` is installed. Upload
   `scripts/attach-passwords-to-existing-users.cjs` from this repo
   into that folder using Cloud Shell's **Upload file** menu.
3. At the Cloud Shell prompt, enter each email and password interactively.
   Password input is hidden and is not saved in shell history:

   ```bash
   cd ~/note-migration
   read -r -p 'Niksy email: ' NIKSY_EMAIL
   read -r -s -p 'Niksy password: ' NIKSY_PASSWORD; echo
   read -r -p 'Miru email: ' MIRU_EMAIL
   read -r -s -p 'Miru password: ' MIRU_PASSWORD; echo
   export NIKSY_EMAIL NIKSY_PASSWORD MIRU_EMAIL MIRU_PASSWORD
   node attach-passwords-to-existing-users.cjs
   ```

4. The dry run must report that both existing UIDs were verified. Then run:

   ```bash
   node attach-passwords-to-existing-users.cjs --apply
   unset NIKSY_EMAIL NIKSY_PASSWORD MIRU_EMAIL MIRU_PASSWORD
   ```

5. Verify that both users now have the expected email address in Firebase
   Console → Authentication → Users. Only then install the new app if Android
   requires uninstalling the old build because of a signing-key mismatch.
   Sign in with the corresponding account on each phone. The same UIDs give
   access to the existing `pairNotes` history and pairing approvals.

The app provides sign-in and password reset, but deliberately has no sign-up
screen. Do not put passwords or a service-account key in this repository.
