package lila.lobby

import lila.game.{ Pov, Source }

final private class AbortListener(
    userRepo: lila.user.UserRepo,
    gameRepo: lila.game.GameRepo,
    seekApi: SeekApi,
    lobbyActor: LobbySyncActor
)(using ec: scala.concurrent.ExecutionContext):

  def apply(pov: Pov): Funit =
    (pov.game.isCorrespondence ?? recreateSeek(pov)) >>-
      cancelColorIncrement(pov) >>-
      lobbyActor.registerAbortedGame(pov.game)

  private def cancelColorIncrement(pov: Pov): Unit =
    if (
      pov.game.source
        .exists(s => s == Source.Lobby || s == Source.Pool) && !gameRepo.fixedColorLobbyCache.get(pov.game.id)
    ) pov.game.userIds match
      case List(u1, u2) =>
        userRepo.incColor(u1, -1)
        userRepo.incColor(u2, 1)
      case _ =>

  private def recreateSeek(pov: Pov): Funit =
    pov.player.userId ?? { aborterId =>
      seekApi.findArchived(pov.gameId) flatMap {
        _ ?? { seek =>
          (seek.user.id != aborterId) ?? {
            worthRecreating(seek) flatMap {
              _ ?? seekApi.insert(Seek renew seek)
            }
          }
        }
      }
    }

  private def worthRecreating(seek: Seek): Fu[Boolean] =
    userRepo byId seek.user.id map {
      _ exists { u =>
        u.enabled.yes && !u.lame
      }
    }
