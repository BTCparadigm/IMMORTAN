package immortan.sqlite

import immortan.{ChannelMaster, PayLinkInfo}
import immortan.utils.{LNUrl, PayRequest}
import fr.acinq.eclair.MilliSatoshi


class SQLitePayMarket(db: DBInterface) {
  def remove(lnUrl: LNUrl): Unit = db.change(PayMarketTable.killSql, lnUrl.request)

  def saveLink(lnUrl: LNUrl, payReq: PayRequest, msat: MilliSatoshi, hash: String): Unit = db txWrap {
    val thumbnailImageString64 = payReq.metaDataImageBase64s.headOption.getOrElse(new String)
    val stamp = System.currentTimeMillis: java.lang.Long
    val lastMsat = msat.toLong: java.lang.Long

    db.change(PayMarketTable.updInfoSql, payReq.metaDataTextPlain, lastMsat, stamp, hash, thumbnailImageString64, lnUrl.request)
    db.change(PayMarketTable.newSql, lnUrl.request, payReq.metaDataTextPlain, lastMsat, stamp, hash, thumbnailImageString64)
    db.change(PayMarketTable.newVirtualSql, s"${lnUrl.uri.getHost} ${payReq.metaDataTextPlain}", lnUrl.request)
    ChannelMaster.next(ChannelMaster.payMarketDbStream)
  }

  def searchLinks(rawSearchQuery: String): RichCursor = db.search(PayMarketTable.searchSql, rawSearchQuery)

  def listRecentLinks(limit: Int): RichCursor = db.select(PayMarketTable.selectRecentSql, limit.toString)

  def toLinkInfo(rc: RichCursor): PayLinkInfo =
    PayLinkInfo(image64 = rc string PayMarketTable.image, lnurlString = rc string PayMarketTable.lnurl,
      text = rc string PayMarketTable.text, lastMsat = MilliSatoshi(rc long PayMarketTable.lastMsat),
      hash = rc string PayMarketTable.hash, lastDate = rc long PayMarketTable.lastDate)
}