import type {
  ActionDashboardCard,
  Dashboard,
  DashboardQueryMetadata,
  DashboardTab,
  QuestionDashboardCard,
  VirtualCard,
  VirtualDashboardCard,
} from "metabase-types/api";

import { createMockCard } from "./card";
import { createMockEntityId } from "./entity-id";
const MOCK_DASHBOARD_ENTITY_ID = createMockEntityId();

export const createMockDashboard = (opts?: Partial<Dashboard>): Dashboard => ({
  id: 1,
  entity_id: MOCK_DASHBOARD_ENTITY_ID,
  created_at: "2024-01-01T00:00:00Z",
  updated_at: "2024-01-01T00:00:00Z",
  collection_id: null,
  name: "Dashboard",
  dashcards: [],
  can_write: true,
  can_restore: false,
  can_delete: false,
  description: "",
  cache_ttl: null,
  "last-edit-info": {
    id: 1,
    email: "admin@metabase.com",
    first_name: "John",
    last_name: "Doe",
    timestamp: "2018-01-01",
  },
  last_used_param_values: {},
  auto_apply_filters: true,
  archived: false,
  public_uuid: null,
  enable_embedding: false,
  embedding_params: null,
  initially_published_at: null,
  width: "fixed",
  creator_id: 1,
  moderation_reviews: [],
  ...opts,
});

const MOCK_DASHBOARD_TAB_ENTITY_ID = createMockEntityId();
export const createMockDashboardTab = (
  opts?: Partial<DashboardTab>,
): DashboardTab => ({
  id: 1,
  dashboard_id: 1,
  name: "Tab 1",
  entity_id: MOCK_DASHBOARD_TAB_ENTITY_ID,
  created_at: "2020-01-01T12:30:30.000000",
  updated_at: "2020-01-01T12:30:30.000000",
  ...opts,
});

const MOCK_DASHBOARD_CARD_ENTITY_ID = createMockEntityId();

export const createMockDashboardCard = (
  opts?: Partial<QuestionDashboardCard>,
): QuestionDashboardCard => ({
  id: 1,
  dashboard_id: 1,
  dashboard_tab_id: null,
  col: 0,
  row: 0,
  card_id: 1,
  size_x: 1,
  size_y: 1,
  entity_id: MOCK_DASHBOARD_CARD_ENTITY_ID,
  visualization_settings: {},
  card: createMockCard(),
  created_at: "2020-01-01T12:30:30.000000",
  updated_at: "2020-01-01T12:30:30.000000",
  inline_parameters: null,
  parameter_mappings: [],
  ...opts,
});

export const createMockVirtualCard = (
  opts?: Partial<VirtualCard>,
): VirtualCard => ({
  id: 1,
  dataset_query: {},
  display: "text",
  name: null,
  visualization_settings: {},
  archived: false,
  ...opts,
});

export const createMockActionDashboardCard = (
  opts?: Partial<ActionDashboardCard>,
): ActionDashboardCard => ({
  ...createMockDashboardCard(),
  action_id: 1,
  action: undefined,
  card: createMockCard({ display: "action" }),
  visualization_settings: {
    "button.label": "Please click me",
    "button.variant": "primary",
    actionDisplayType: "button",
    virtual_card: createMockVirtualCard({ display: "action" }),
  },
  ...opts,
});

type VirtualDashboardCardOpts = Partial<
  Omit<VirtualDashboardCard, "visualization_settings">
> & {
  visualization_settings?: Partial<
    VirtualDashboardCard["visualization_settings"]
  >;
};

export const createMockVirtualDashCard = (
  opts?: VirtualDashboardCardOpts,
): VirtualDashboardCard => {
  const card = createMockVirtualCard(
    opts?.card || opts?.visualization_settings?.virtual_card,
  );
  return {
    id: 1,
    dashboard_id: 1,
    dashboard_tab_id: null,
    col: 0,
    row: 0,
    size_x: 1,
    size_y: 1,
    inline_parameters: null,
    entity_id: createMockEntityId(),
    created_at: "2020-01-01T12:30:30.000000",
    updated_at: "2020-01-01T12:30:30.000000",
    card_id: null,
    card,
    ...opts,
    visualization_settings: {
      ...opts?.visualization_settings,
      virtual_card: card,
    },
  };
};

export const createMockTextDashboardCard = ({
  text,
  ...opts
}: VirtualDashboardCardOpts & { text?: string } = {}): VirtualDashboardCard =>
  createMockVirtualDashCard({
    ...opts,
    card: createMockVirtualCard({ display: "text" }),
    visualization_settings: {
      text: text ?? "Body Text",
    },
  });

type HeadingDashboardCardOpts = VirtualDashboardCardOpts & {
  text?: string;
};

export const createMockHeadingDashboardCard = ({
  text = "Heading Text",
  ...opts
}: HeadingDashboardCardOpts = {}): VirtualDashboardCard =>
  createMockVirtualDashCard({
    ...opts,
    card: createMockVirtualCard({ display: "heading" }),
    visualization_settings: { text },
  });

export const createMockLinkDashboardCard = ({
  visualization_settings,
  ...opts
}: VirtualDashboardCardOpts & { url?: string } = {}): VirtualDashboardCard =>
  createMockVirtualDashCard({
    ...opts,
    card: createMockVirtualCard({ display: "link" }),
    visualization_settings: {
      link: {
        ...visualization_settings?.link,
        url: opts?.url ?? visualization_settings?.link?.url ?? "Link Text",
      },
    },
  });

export const createMockIFrameDashboardCard = ({
  visualization_settings,
  ...opts
}: VirtualDashboardCardOpts & { iframe?: string } = {}): VirtualDashboardCard =>
  createMockVirtualDashCard({
    ...opts,
    card: createMockVirtualCard({ display: "iframe" }),
    visualization_settings: {
      iframe:
        opts?.iframe ??
        visualization_settings?.iframe ??
        "<iframe src='https://example.com'></iframe>",
    },
  });

export const createMockPlaceholderDashboardCard = ({
  visualization_settings,
  ...opts
}: VirtualDashboardCardOpts = {}): VirtualDashboardCard =>
  createMockVirtualDashCard({
    ...opts,
    card: createMockVirtualCard({ display: "placeholder" }),
  });

export const createMockDashboardQueryMetadata = (
  opts?: Partial<DashboardQueryMetadata>,
): DashboardQueryMetadata => ({
  databases: [],
  tables: [],
  fields: [],
  cards: [],
  dashboards: [],
  ...opts,
});
