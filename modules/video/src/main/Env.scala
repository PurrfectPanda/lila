package lila.video

import play.api.libs.ws.StandaloneWSClient
import play.api.Mode
import com.softwaremill.macwire.*
import lila.common.autoconfig.{ *, given }
import play.api.Configuration
import scala.concurrent.duration.*

import lila.common.config.*

@Module
private class VideoConfig(
    @ConfigName("collection.video") val videoColl: CollName,
    @ConfigName("collection.view") val viewColl: CollName,
    @ConfigName("sheet.url") val sheetUrl: String,
    @ConfigName("sheet.delay") val sheetDelay: FiniteDuration,
    @ConfigName("youtube.url") val youtubeUrl: String,
    @ConfigName("youtube.api_key") val youtubeApiKey: Secret,
    @ConfigName("youtube.max") val youtubeMax: Max,
    @ConfigName("youtube.delay") val youtubeDelay: FiniteDuration
)

final class Env(
    appConfig: Configuration,
    ws: StandaloneWSClient,
    scheduler: akka.actor.Scheduler,
    db: lila.db.Db,
    cacheApi: lila.memo.CacheApi,
    mode: Mode
)(using ec: scala.concurrent.ExecutionContext):

  private val config = appConfig.get[VideoConfig]("video")(AutoConfig.loader)

  lazy val api = new VideoApi(
    cacheApi = cacheApi,
    videoColl = db(config.videoColl),
    viewColl = db(config.viewColl)
  )

  private lazy val sheet = new VideoSheet(ws, config.sheetUrl, api)

  private lazy val youtube = new Youtube(
    ws = ws,
    url = config.youtubeUrl,
    apiKey = config.youtubeApiKey,
    max = config.youtubeMax,
    api = api
  )

  def cli =
    new lila.common.Cli:
      def process = { case "video" :: "sheet" :: Nil =>
        sheet.fetchAll map { nb => s"Processed $nb videos" }
      }

  if (mode == Mode.Prod)
    scheduler.scheduleWithFixedDelay(config.sheetDelay, config.sheetDelay) { () =>
      sheet.fetchAll.logFailure(logger).unit
    }

    scheduler.scheduleWithFixedDelay(config.youtubeDelay, config.youtubeDelay) { () =>
      youtube.updateAll.logFailure(logger).unit
    }
