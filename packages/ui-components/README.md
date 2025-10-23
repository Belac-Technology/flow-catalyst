# @flowcatalyst/ui-components

FlowCatalyst UI component library built with Vue 3, TypeScript, and Tailwind CSS.

## Installation

```bash
npm install @flowcatalyst/ui-components
# or
bun add @flowcatalyst/ui-components
```

## Usage

```vue
<script setup lang="ts">
import { Button, Card } from '@flowcatalyst/ui-components'
import '@flowcatalyst/ui-components/style.css'
</script>

<template>
  <Card variant="elevated">
    <template #header>
      <h2>Card Title</h2>
    </template>

    <p>Card content goes here</p>

    <template #footer>
      <Button variant="primary">Action</Button>
    </template>
  </Card>
</template>
```

## Components

### Button
Versatile button component with multiple variants and sizes.

**Props:**
- `variant`: 'primary' | 'secondary' | 'outline' | 'ghost' (default: 'primary')
- `size`: 'sm' | 'md' | 'lg' (default: 'md')
- `type`: 'button' | 'submit' | 'reset' (default: 'button')
- `disabled`: boolean (default: false)

### Card
Container component with optional header and footer slots.

**Props:**
- `variant`: 'default' | 'bordered' | 'elevated' (default: 'default')

**Slots:**
- `header`: Optional header content
- `default`: Main card content
- `footer`: Optional footer content

## Development

```bash
# Watch mode
bun run dev

# Build
bun run build

# Type check
bun run type-check
```

## Tailwind UI Plus

This library is designed to work seamlessly with Tailwind UI Plus components. You can copy components from Tailwind UI Plus and integrate them into this library following the same patterns.

## License

Apache-2.0
