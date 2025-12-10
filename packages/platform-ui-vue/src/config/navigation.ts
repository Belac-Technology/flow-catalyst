export interface NavItem {
  label: string;
  icon: string;
  route?: string;
  children?: NavItem[];
  expanded?: boolean;
}

export interface NavGroup {
  label: string;
  items: NavItem[];
}

export const NAVIGATION_CONFIG: NavGroup[] = [
  {
    label: 'Overview',
    items: [
      {
        label: 'Dashboard',
        icon: 'pi pi-home',
        route: '/dashboard',
      },
    ],
  },
  {
    label: 'Identity & Access',
    items: [
      {
        label: 'User Management',
        icon: 'pi pi-users',
        route: '/users',
        children: [
          { label: 'All Users', icon: 'pi pi-list', route: '/users' },
          { label: 'Application Assignments', icon: 'pi pi-sitemap', route: '/users/applications' },
          { label: 'Client Assignments', icon: 'pi pi-building', route: '/users/clients' },
        ],
      },
    ],
  },
  {
    label: 'Authentication',
    items: [
      {
        label: 'Domain IDPs',
        icon: 'pi pi-id-card',
        route: '/authentication/domain-idps',
      },
      {
        label: 'Anchor Domains',
        icon: 'pi pi-globe',
        route: '/authentication/anchor-domains',
      },
    ],
  },
  {
    label: 'Authorization',
    items: [
      {
        label: 'Roles',
        icon: 'pi pi-shield',
        route: '/authorization/roles',
      },
      {
        label: 'Permissions',
        icon: 'pi pi-lock',
        route: '/authorization/permissions',
      },
    ],
  },
  {
    label: 'Platform',
    items: [
      {
        label: 'Applications',
        icon: 'pi pi-th-large',
        route: '/applications',
      },
      {
        label: 'Clients',
        icon: 'pi pi-building',
        route: '/clients',
      },
    ],
  },
  {
    label: 'Messaging',
    items: [
      {
        label: 'Event Types',
        icon: 'pi pi-bolt',
        route: '/event-types',
      },
      {
        label: 'Subscriptions',
        icon: 'pi pi-bell',
        route: '/subscriptions',
      },
      {
        label: 'Dispatch Jobs',
        icon: 'pi pi-send',
        route: '/dispatch-jobs',
      },
    ],
  },
];
