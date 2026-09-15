import { cp, mkdir } from 'node:fs/promises';

// 기존 static 화면은 그대로 두고 React 전용 하위 디렉터리에만 복사한다.
const destination = new URL('../../src/main/resources/static/react/', import.meta.url);
await mkdir(destination, { recursive: true });
await cp(new URL('../dist/', import.meta.url), destination, { recursive: true });
console.log('React build staged at src/main/resources/static/react/');
