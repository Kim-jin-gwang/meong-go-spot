import { mkdir, writeFile } from 'node:fs/promises';
import { generateKeyPairSync, randomBytes } from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const secretRoot = path.join(root, '.secrets');
const sessionDir = path.join(secretRoot, 'auth-sessions-dev');
await mkdir(secretRoot, { recursive: true, mode: 0o700 });
try {
  await mkdir(sessionDir, { mode: 0o700 });
} catch (error) {
  if (error.code === 'EEXIST') {
    throw new Error('이미 .secrets/auth-sessions-dev가 있습니다. 기존 키는 덮어쓰지 않았습니다.');
  }
  throw error;
}

const kid = 'local-dev-' + randomBytes(8).toString('hex');
const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const privatePath = path.join(sessionDir, 'private-key.pem');
const jwksPath = path.join(sessionDir, 'public-jwks.json');
const configPath = path.join(sessionDir, 'dev.yml');
const localPath = (value) => value.replaceAll('\\', '/');
const fileOptions = { flag: 'wx', mode: 0o600 };
await writeFile(privatePath, privateKey.export({ type: 'pkcs8', format: 'pem' }), fileOptions);
await writeFile(jwksPath, JSON.stringify({ keys: [{ ...publicKey.export({ format: 'jwk' }), kid, alg: 'RS256', use: 'sig' }] }) + '\n', fileOptions);
await writeFile(configPath, JSON.stringify({
  AUTH_ACCESS_TOKEN_TTL: 'PT15M',
  AUTH_REFRESH_TOKEN_TTL: 'P30D',
  AUTH_JWT_CLOCK_SKEW: 'PT30S',
  AUTH_JWT_ISSUER: 'meonggocuisine-auth',
  AUTH_JWT_AUDIENCE: 'meonggocuisine-api',
  AUTH_JWT_CLIENT_ID: 'meonggocuisine-android',
  AUTH_JWT_ACTIVE_KID: kid,
  AUTH_JWT_PRIVATE_KEY_PATH: localPath(privatePath),
  AUTH_JWT_PUBLIC_JWKS_PATH: localPath(jwksPath),
  AUTH_LOGIN_ID_HMAC_KEY_V1: randomBytes(32).toString('base64'),
}, null, 2) + '\n', fileOptions);

console.log('로컬 전용 JWT·로그인 키를 .secrets/auth-sessions-dev에 생성했습니다.');
console.log(`SPRING_CONFIG_ADDITIONAL_LOCATION=${pathToFileURL(path.join(secretRoot, 'auth-dev', 'dev.yml')).href},${pathToFileURL(configPath).href}`);
console.log('가입 설정은 node scripts/setup-auth-dev.mjs로 먼저 생성하세요. 기존 전화번호 키는 유지됩니다.');
