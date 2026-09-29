import { mkdir, writeFile } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const secretRoot = path.join(root, '.secrets');
const authDir = path.join(secretRoot, 'auth-dev');

// 기존 키를 교체하면 저장한 전화번호를 읽지 못하므로 재실행 시 덮어쓰지 않는다.
await mkdir(secretRoot, { recursive: true, mode: 0o700 });
try {
  await mkdir(authDir, { mode: 0o700 });
} catch (error) {
  if (error.code === 'EEXIST') {
    throw new Error('이미 .secrets/auth-dev가 있습니다. 기존 설정을 사용하세요. 키는 덮어쓰지 않았습니다.');
  }
  throw error;
}

const keyringPath = path.join(authDir, 'phone-keyring.json');
const redisPath = path.join(authDir, 'redis.properties');
const devConfig = path.join(authDir, 'dev.yml');
const newKey = () => randomBytes(32).toString('base64');
const localPath = (value) => value.replaceAll('\\', '/');

await writeFile(keyringPath, JSON.stringify({ currentKid: 'V1', keys: { V1: newKey() } }) + '\n', { flag: 'wx', mode: 0o600 });
await writeFile(redisPath, 'spring.data.redis.password=redis-local-only\n', { flag: 'wx', mode: 0o600 });
// JSON은 YAML 1.2의 부분집합이며 경로·키 escaping을 안전하게 유지한다.
await writeFile(devConfig, JSON.stringify({
  PHONE_DATA_ENCRYPTION_KEYRING_PATH: localPath(keyringPath),
  PHONE_LOOKUP_HMAC_KEY_V1: newKey(),
  PHONE_OTP_HMAC_KEY_V1: newKey(),
  AUTH_IP_HMAC_KEY_V1: newKey(),
  SMS_PROVIDER: 'FAKE',
  SMS_FAKE_FIXED_OTP: '000000',
  REDIS_CREDENTIALS_PATH: localPath(redisPath),
}, null, 2) + '\n', { flag: 'wx', mode: 0o600 });

console.log('로컬 전용 인증 설정을 .secrets/auth-dev에 생성했습니다. 키 값은 출력하지 않습니다.');
console.log(`SPRING_CONFIG_ADDITIONAL_LOCATION=${pathToFileURL(devConfig).href}`);
console.log('위 환경변수를 설정한 뒤 npm run dev:be를 실행하세요.');
