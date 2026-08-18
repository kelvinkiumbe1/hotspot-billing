/**
 * The one icon component for the whole app.
 *
 * We render Lucide icons — crisp, single-weight outline strokes that read as
 * premium and stay razor-sharp at any size — but keep the old call site API
 * (`<Icon name="wifi_tethering" filled />`) so nothing else had to change.
 * `name` is still the Material-Symbols name it always was; the map below
 * translates it to a Lucide glyph.
 *
 * Two wins beyond looks: the SVGs are inlined into the bundle, so icons work
 * OFFLINE (the captive portal has no internet until the customer pays) and
 * there's no font-CDN request or flash-of-missing-glyph on load.
 *
 * Sizing: `size="1em"` makes each icon scale with the surrounding font-size,
 * so the existing `text-[20px]` / `text-[32px]` classes keep controlling size
 * exactly as they did with the icon font. Colour rides `currentColor`, so
 * `text-primary` etc. still tint them.
 */
import {
  Landmark, Wallet, CircleUserRound, Plus, Store, PlusCircle, ShieldUser, ShieldCheck,
  ArrowLeft, ArrowRight, ClipboardCheck, Paperclip, IdCard, Zap, CalendarDays, Phone,
  Megaphone, PartyPopper, MessageCircle, Check, CircleCheck, ListChecks, ChevronLeft,
  ChevronRight, X, CloudOff, Ticket, Copy, CreditCard, LayoutDashboard, Trash2,
  MonitorSmartphone, Ban, Download, HardHat, CircleAlert, CalendarClock, ChevronUp,
  ChevronDown, Fingerprint, MessagesSquare, LayoutGrid, Users, History, Image as ImageIcon,
  Info, LineChart, Package, KeyRound, Network, MapPin, Lock, LogOut, Award, Mail,
  Map as MapIcon, Menu, Ellipsis, TimerReset, Activity, Bell, BellRing, Palette, Banknote,
  Hourglass, Percent, User, UserPlus, UserSearch, UserCog, Camera, Spline, Printer,
  LoaderCircle, QrCode, ReceiptText, Gift, RefreshCw, Minus, Router, Tag, BarChart3, Save,
  Clock, Search, Send, HandCoins, Bot, Smartphone, MessageSquare, Gauge, Headset, ListTodo,
  CircleCheckBig, Timer, TrendingDown, TrendingUp, SlidersHorizontal, Eye, EyeOff, Wifi,
  RadioTower, Settings, Circle, ShieldAlert, BadgeCheck, BanknoteX, Inbox,
  CircleQuestionMark, UserX, WifiOff, CalendarX, HeartPulse, DatabaseBackup, ServerCog,
  Pencil, Globe, Languages, BatteryCharging, Cable, Share2,
} from 'lucide-react'

const MAP = {
  account_balance: Landmark,
  account_balance_wallet: Wallet,
  account_circle: CircleUserRound,
  add: Plus,
  add_business: Store,
  add_circle: PlusCircle,
  admin_panel_settings: ShieldUser,
  arrow_back: ArrowLeft,
  arrow_forward: ArrowRight,
  assignment_turned_in: ClipboardCheck,
  attach_file: Paperclip,
  badge: IdCard,
  bolt: Zap,
  calendar_month: CalendarDays,
  call: Phone,
  campaign: Megaphone,
  celebration: PartyPopper,
  chat: MessageCircle,
  check: Check,
  check_circle: CircleCheck,
  checklist: ListChecks,
  chevron_left: ChevronLeft,
  chevron_right: ChevronRight,
  close: X,
  cloud_off: CloudOff,
  confirmation_number: Ticket,
  content_copy: Copy,
  credit_card: CreditCard,
  device_hub: Share2,
  edit: Pencil,
  language: Languages,
  public: Globe,
  battery_charging_full: BatteryCharging,
  settings_input_hdmi: Cable,
  content_copy: Copy,
  dashboard: LayoutDashboard,
  delete: Trash2,
  deleted: Trash2,
  devices: MonitorSmartphone,
  disable: Ban,
  disabled: Ban,
  download: Download,
  engineering: HardHat,
  error: CircleAlert,
  event: CalendarClock,
  expand_less: ChevronUp,
  expand_more: ChevronDown,
  fingerprint: Fingerprint,
  forum: MessagesSquare,
  grid_view: LayoutGrid,
  group: Users,
  history: History,
  image: ImageIcon,
  info: Info,
  insights: LineChart,
  install_mobile: Download,
  inventory_2: Package,
  key: KeyRound,
  lan: Network,
  location_on: MapPin,
  lock: Lock,
  logout: LogOut,
  loyalty: Award,
  mail: Mail,
  map: MapIcon,
  menu: Menu,
  more_horiz: Ellipsis,
  more_time: TimerReset,
  network_check: Activity,
  notifications: Bell,
  notifications_active: BellRing,
  outbox: Send,
  palette: Palette,
  password: KeyRound,
  pay: Banknote,
  payments: Banknote,
  pending: Hourglass,
  pending_actions: Hourglass,
  percent: Percent,
  person: User,
  person_add: UserPlus,
  person_search: UserSearch,
  phone: Phone,
  photo_camera: Camera,
  polyline: Spline,
  print: Printer,
  progress_activity: LoaderCircle,
  qr_code_2: QrCode,
  receipt_long: ReceiptText,
  redeem: Gift,
  refresh: RefreshCw,
  remove: Minus,
  role: UserCog,
  router: Router,
  sale: Tag,
  sales: BarChart3,
  save: Save,
  schedule: Clock,
  search: Search,
  security: ShieldCheck,
  send: Send,
  send_money: HandCoins,
  settings: Settings,
  smart_toy: Bot,
  smartphone: Smartphone,
  sms: MessageSquare,
  speed: Gauge,
  storefront: Store,
  support_agent: Headset,
  task: ListTodo,
  task_alt: CircleCheckBig,
  ticket: Ticket,
  tickets: Ticket,
  timeline: Activity,
  timer: Timer,
  trending_down: TrendingDown,
  trending_up: TrendingUp,
  tune: SlidersHorizontal,
  // Revenue Guard
  policy: ShieldAlert,
  verified_user: BadgeCheck,
  money_off: BanknoteX,
  inbox: Inbox,
  help: CircleQuestionMark,
  person_alert: UserX,
  wifi_off: WifiOff,
  event_busy: CalendarX,
  // System health
  monitor_heart: HeartPulse,
  backup: DatabaseBackup,
  dns: ServerCog,
  visibility: Eye,
  visibility_off: EyeOff,
  wifi: Wifi,
  wifi_tethering: RadioTower,
}

export function Icon({ name, filled = false, className = '', style }) {
  const Glyph = MAP[name] || Circle
  if (import.meta.env.DEV && !MAP[name]) {
    // Surface a missed mapping in dev instead of silently drawing a dot.
    console.warn(`[Icon] no Lucide mapping for "${name}"`)
  }
  return (
    <Glyph
      aria-hidden="true"
      className={`inline-block shrink-0 align-[-0.125em] ${className}`}
      style={style}
      size="1em"
      strokeWidth={filled ? 2.2 : 1.75}
      {...(filled ? { fill: 'currentColor', fillOpacity: 0.15 } : {})}
    />
  )
}
