import {
  LayoutDashboard,
  Sparkles,
  Inbox,
  ListChecks,
  CalendarDays,
  Network,
  Workflow,
  Smartphone,
  Settings,
  type LucideIcon,
} from "lucide-react";

export interface NavItem {
  href: string;
  label: string;
  short: string;
  description: string;
  icon: LucideIcon;
  badgeKey?: "tasks" | "inbox" | "attention";
}

export interface NavGroup {
  label: string;
  items: NavItem[];
}

export const NAV_GROUPS: NavGroup[] = [
  {
    label: "Core",
    items: [
      {
        href: "/",
        label: "Command Center",
        short: "Command",
        description: "Your day at a glance",
        icon: LayoutDashboard,
      },
      {
        href: "/ask",
        label: "Ask Jarvis",
        short: "Ask",
        description: "Voice and text intelligence",
        icon: Sparkles,
      },
    ],
  },
  {
    label: "Knowledge",
    items: [
      {
        href: "/inbox",
        label: "Signal Inbox",
        short: "Inbox",
        description: "WhatsApp and notifications",
        icon: Inbox,
        badgeKey: "inbox",
      },
      {
        href: "/tasks",
        label: "Mission Queue",
        short: "Tasks",
        description: "Assignments and deadlines",
        icon: ListChecks,
        badgeKey: "tasks",
      },
      {
        href: "/calendar",
        label: "Chrono",
        short: "Chrono",
        description: "Schedule and exams",
        icon: CalendarDays,
      },
      {
        href: "/memory",
        label: "Memory Graph",
        short: "Memory",
        description: "Entities and relationships",
        icon: Network,
      },
    ],
  },
  {
    label: "System",
    items: [
      {
        href: "/automations",
        label: "Automations",
        short: "Automations",
        description: "n8n workflow modules",
        icon: Workflow,
      },
      {
        href: "/devices",
        label: "Devices",
        short: "Devices",
        description: "Android companion",
        icon: Smartphone,
      },
      {
        href: "/settings",
        label: "Settings",
        short: "Settings",
        description: "Models, privacy, data",
        icon: Settings,
      },
    ],
  },
];

export const NAV_ITEMS = NAV_GROUPS.flatMap((g) => g.items);

export function findNav(pathname: string): NavItem {
  const exact = NAV_ITEMS.find((item) => item.href === pathname);
  if (exact) return exact;
  const nested = NAV_ITEMS.filter((item) => item.href !== "/").find((item) =>
    pathname.startsWith(item.href),
  );
  return nested ?? NAV_ITEMS[0];
}
