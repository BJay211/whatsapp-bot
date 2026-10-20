# Bonjee Dashboard

Next.js dashboard to view your briefing summaries, deadlines, action items, and timeline.

## Tech Stack
- Next.js 14 (App Router)
- TypeScript
- Tailwind CSS
- Prisma ORM (for Postgres)
- shadcn/ui components

## Getting Started

### Local Development
```bash
cd apps/dashboard
npm install
cp .env.example .env.local
# Fill in DATABASE_URL and other env vars
npm run dev
```

### Environment Variables
```env
DATABASE_URL="postgresql://user:password@host:5432/db"
NEXTAUTH_URL="http://localhost:3000"
NEXTAUTH_SECRET="your-secret"
N8N_BASE_URL="http://localhost:5678"
```

### Build for Production
```bash
npm run build
npm start
```

## Features
- Latest summary with urgent/deadlines/action items
- Timeline of all messages and notifications
- Deadlines view (sorted by due date, filter completed)
- Action items view
- Settings to configure filters and timing
- Mobile responsive

## Deploy to Render
This is configured in the root `infra/render.yaml` to deploy as a web service. Make sure DATABASE_URL is connected to the Render Postgres instance.
