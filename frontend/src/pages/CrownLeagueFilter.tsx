import { LeagueSelectorPage } from './LeagueFilter'

const CrownLeagueFilter = () => (
  <LeagueSelectorPage
    title="皇冠比赛选择"
    cardTitle="皇冠关注联赛"
    description="这里按皇冠抓到的原始联赛名筛选；勾选后，皇冠比赛监控和 TG 只处理这些联赛。"
    sourceKey="crown"
  />
)

export default CrownLeagueFilter
