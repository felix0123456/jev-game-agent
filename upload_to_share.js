const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const BASE_URL = 'https://file.felixresch.com';
const SHARE_ID = 'jev-game-agent';
const APK_PATH = path.join(__dirname, 'jev-game-agent.apk');

async function main() {
    console.log('1. Signing in to Pingvin Share...');
    const signInRes = await fetch(`${BASE_URL}/api/auth/signIn`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            email: 'agent@felixresch.com',
            password: 'AgentPassword123!'
        })
    });

    if (!signInRes.ok) {
        throw new Error(`Sign in failed (${signInRes.status}): ${await signInRes.text()}`);
    }

    const cookieHeader = signInRes.headers.get('set-cookie') || '';
    const tokenCookie = cookieHeader.split(';')[0];
    const signInJson = await signInRes.json();
    const token = signInJson.accessToken;

    const authHeaders = {
        'Cookie': tokenCookie,
        'Authorization': `Bearer ${token}`
    };

    console.log('2. Preparing share:', SHARE_ID);
    try {
        await fetch(`${BASE_URL}/api/shares/${SHARE_ID}`, {
            method: 'DELETE',
            headers: authHeaders
        });
    } catch (e) {
        // ignore
    }

    // Expiration: 1 year in the future
    const futureDate = new Date(Date.now() + 365 * 24 * 60 * 60 * 1000).toISOString();

    const createRes = await fetch(`${BASE_URL}/api/shares`, {
        method: 'POST',
        headers: {
            ...authHeaders,
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            id: SHARE_ID,
            expiration: futureDate,
            recipients: []
        })
    });

    if (!createRes.ok) {
        throw new Error(`Create share failed (${createRes.status}): ${await createRes.text()}`);
    }
    console.log('Share container created successfully with expiration:', futureDate);

    console.log('3. Uploading APK binary...');
    const fileBuffer = fs.readFileSync(APK_PATH);
    const fileId = crypto.randomUUID();
    const fileName = 'jev-game-agent.apk';

    const uploadUrl = new URL(`${BASE_URL}/api/shares/${SHARE_ID}/files`);
    uploadUrl.searchParams.set('id', fileId);
    uploadUrl.searchParams.set('name', fileName);
    uploadUrl.searchParams.set('chunkIndex', '0');
    uploadUrl.searchParams.set('totalChunks', '1');

    const uploadRes = await fetch(uploadUrl.toString(), {
        method: 'POST',
        headers: {
            ...authHeaders,
            'Content-Type': 'application/octet-stream',
            'Content-Length': fileBuffer.length.toString()
        },
        body: fileBuffer
    });

    if (!uploadRes.ok) {
        throw new Error(`Upload failed (${uploadRes.status}): ${await uploadRes.text()}`);
    }
    console.log('Upload completed:', await uploadRes.text());

    console.log('4. Completing share...');
    const completeRes = await fetch(`${BASE_URL}/api/shares/${SHARE_ID}/complete`, {
        method: 'POST',
        headers: authHeaders
    });

    if (!completeRes.ok) {
        throw new Error(`Complete share failed (${completeRes.status}): ${await completeRes.text()}`);
    }
    console.log('Share completed:', await completeRes.text());

    const shareUrl = `${BASE_URL}/share/${SHARE_ID}`;
    console.log('\n========================================');
    console.log('SUCCESS! Public APK download URL:');
    console.log(shareUrl);
    console.log('Direct file URL:');
    console.log(`${BASE_URL}/api/shares/${SHARE_ID}/files/${fileId}`);
    console.log('========================================\n');
}

main().catch(err => {
    console.error('Fatal error:', err);
    process.exit(1);
});
