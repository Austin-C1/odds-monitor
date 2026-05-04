import { LeagueSelectorPage } from './LeagueFilter'

const DefaultTracking = () => (
  <LeagueSelectorPage
    title="默认追踪"
    cardTitle="默认追踪联赛"
    description="这里是系统默认追踪名单，也包含联赛筛选页手动勾选的联赛；取消勾选并保存后，该联赛不再进入比赛监控和 TG 推送。"
  />
)

export default DefaultTracking
