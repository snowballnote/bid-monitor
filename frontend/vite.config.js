import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const proxy = {
    '^/(api(?:/|$)|submissions(?:/|$)|documents(?:/|$)|performances(?:/|$)|bids(?:/|$)|notices(?:/|$)|notifications(?:/|$)|common\\.css|home\\.(css|js)|style\\.css|app\\.js)': {
      target: env.SPRING_BOOT_URL || 'http://localhost:8080',
      changeOrigin: true,
    },
  };
  return {
    plugins: [react()],
    base: '/react/',
    server: { host: '127.0.0.1', port: 5173, strictPort: true, proxy,
      fs: { allow: [fileURLToPath(new URL('..', import.meta.url))] } },
    preview: { host: '127.0.0.1', port: 4173, strictPort: true, proxy },
    build: { outDir: 'dist' },
  };
});
