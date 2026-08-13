import { defineConfig } from 'vitest/config';

// Test-only: nothing in bootJar, the Docker image, or what the browser downloads
// knows Vitest exists. The page stays plain ES modules served as-is.
export default defineConfig({
    test: {
        environment: 'jsdom',
        include: ['src/test/javascript/**/*.test.js'],
        root: '.',
    },
});
