import { LeagueSelectorPage } from './LeagueFilter'

const PinnacleLeagueFilter = () => (
  <LeagueSelectorPage
    title="平博比赛选择"
    cardTitle="平博关注联赛"
    description="这里按平博抓到的原始联赛名筛选；勾选后，平博比赛监控和 TG 只处理这些联赛。"
    sourceKey="pinnacle"
  />
)

export default PinnacleLeagueFilter
