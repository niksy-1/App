// One-time account upgrade. Run in Cloud Shell with Application Default
// Credentials after enabling Firebase Authentication's Email/Password provider.
// The existing UIDs remain unchanged, so Firestore notes and approvals stay put.
const {initializeApp, applicationDefault} = require("firebase-admin/app");
const {getAuth} = require("firebase-admin/auth");

const accounts = [
  {
    name: "Niksy",
    uid: "VG2S5glfWVbaZd2TxflIMZjcgPz1",
    email: process.env.NIKSY_EMAIL?.trim(),
    password: process.env.NIKSY_PASSWORD,
  },
  {
    name: "Miru",
    uid: "6voEdctatob28aCJG2MQAz9SaL82",
    email: process.env.MIRU_EMAIL?.trim(),
    password: process.env.MIRU_PASSWORD,
  },
];

async function main() {
  const apply = process.argv.includes("--apply");
  if (accounts.some(({email, password}) =>
    !email || !password || password.length < 6)) {
    throw new Error("Set both emails and passwords of at least 6 characters.");
  }
  if (accounts[0].email === accounts[1].email) {
    throw new Error("Use a different email address for each account.");
  }

  initializeApp({
    credential: applicationDefault(),
    projectId: "widget-33ff3",
  });
  const auth = getAuth();
  for (const account of accounts) {
    const current = await auth.getUser(account.uid);
    if (current.disabled) throw new Error(`${account.name} is disabled.`);
    if (current.email && current.email !== account.email) {
      throw new Error(`${account.name} already has a different email.`);
    }
    try {
      const owner = await auth.getUserByEmail(account.email);
      if (owner.uid !== account.uid) {
        throw new Error(`${account.name}'s email belongs to another account.`);
      }
    } catch (error) {
      if (error.code !== "auth/user-not-found") throw error;
    }
    console.log(`${account.name}: existing UID verified`);
  }

  if (!apply) {
    console.log("Dry run complete. No accounts changed.");
    return;
  }

  for (const account of accounts) {
    const updated = await auth.updateUser(account.uid, {
      email: account.email,
      password: account.password,
      displayName: account.name,
    });
    if (updated.uid !== account.uid || updated.email !== account.email) {
      throw new Error(`${account.name} could not be verified after update.`);
    }
    console.log(`${account.name}: sign-in attached to original UID`);
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
