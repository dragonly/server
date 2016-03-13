package konstructs

import akka.actor.{ Actor, ActorRef, Props, Stash }
import konstructs.plugin.{ PluginConstructor, Config, ListConfig }
import konstructs.api._
import konstructs.api.messages.{ EventBlockRemoved, EventBlockUpdated, PutBlock, RemoveBlock, ViewBlock, ReplaceBlocks, BoxQuery }
import collection.JavaConversions._

class UniverseActor(
                     name: String,
                     jsonStorage: ActorRef,
                     binaryStorage: ActorRef,
                     inventoryManager: ActorRef,
                     konstructing: ActorRef,
                     blockManager: ActorRef,
                     chatFilters: Seq[ActorRef],
                     blockUpdateEvents: Seq[ActorRef],
                     primaryInteractionFilters: Seq[ActorRef],
                     secondaryInteractionFilters: Seq[ActorRef],
                     tertiaryInteractionFilters: Seq[ActorRef]
                   ) extends Actor with Stash {

  import UniverseActor._

  var generator: ActorRef = null
  var db: ActorRef = null

  private var nextPid = 0

  def playerActorId(pid: Int) = s"player-$pid"

  def allPlayers(except: Option[Int] = None) = {
    val players = context.children.filter(_.path.name.startsWith("player-"))
    except match {
      case Some(pid) =>
        players.filter(_.path.name != playerActorId(pid))
      case None => players
    }
  }

  def player(nick: String, password: String) {
    val player = context.actorOf(
      PlayerActor.props(nextPid, nick, password, sender, db, self, jsonStorage, protocol.Position(0,512f,0,0,0)),
      playerActorId(nextPid)
    )
    allPlayers(except = Some(nextPid)).foreach(_ ! PlayerActor.SendInfo(player))
    allPlayers(except = Some(nextPid)).foreach(player ! PlayerActor.SendInfo(_))
    nextPid = nextPid + 1
  }

  blockManager ! GetBlockFactory

  def receive = {
    case factory: BlockFactory =>
      generator = context.actorOf(GeneratorActor.props(jsonStorage, binaryStorage, factory))
      db = context.actorOf(DbActor.props(self, generator, binaryStorage, factory))
      context.become(ready)
      unstashAll()
    case _ =>
      stash()
  }

  def ready: Receive = {
    case CreatePlayer(nick, password) =>
      player(nick, password)
    case m: PlayerActor.PlayerMovement =>
      allPlayers(except = Some(m.pid)).foreach(_ ! m)
    case l: PlayerActor.PlayerLogout =>
      allPlayers(except = Some(l.pid)).foreach(_ ! l)
    case b: DbActor.BlockList =>
      allPlayers().foreach(_ ! b)
    case s: Say =>
      val filters = chatFilters :+ self
      filters.head.forward(SayFilter(filters.tail, s))
    case s: SayFilter =>
      allPlayers().foreach(_.forward(Said(s.message.text)))
    case s: Said =>
      allPlayers().foreach(_.forward(s))
    case i: InteractPrimary =>
      val filters = primaryInteractionFilters :+ self
      filters.head.forward(InteractPrimaryFilter(filters.tail, i))
    case i: InteractPrimaryFilter =>
      if(i.message.pos != null) {
        db.tell(DbActor.RemoveBlock(i.message.pos, i.message.sender), blockManager)
        blockUpdateEvents.foreach(e => e ! new EventBlockRemoved(Set(i.message.pos)))
      }
      if(i.message.block != null) {
        i.message.sender ! ReceiveStack(Stack.createFromBlock(i.message.block))
      }
    case i: InteractSecondary =>
      val filters = secondaryInteractionFilters :+ self
      filters.head.forward(InteractSecondaryFilter(filters.tail, i))
    case i: InteractSecondaryFilter =>
      if(i.message.pos != null) {
        if(i.message.block != null) {
          blockManager.tell(BlockMetaActor.PutBlockTo(i.message.pos, i.message.block, db), i.message.sender)
          blockUpdateEvents.foreach(e => e ! new EventBlockUpdated(Map(i.message.pos -> i.message.block.getType)))
        }
      } else {
        if(i.message.block != null) {
          i.message.sender ! new ReceiveStack(Stack.createFromBlock(i.message.block))
        }
      }
    case i: InteractTertiary =>
      val filters = tertiaryInteractionFilters :+ self
      filters.head.forward(InteractTertiaryFilter(filters.tail, i))
    case i: InteractTertiaryFilter =>
      if(i.message.block != null) {
        i.message.sender ! new ReceiveStack(Stack.createFromBlock(i.message.block))
      }
    case p: PutBlock =>
      blockManager.forward(BlockMetaActor.PutBlockTo(p.getPosition, p.getBlock, db))
      blockUpdateEvents.foreach(e => e ! new EventBlockUpdated(Map(p.getPosition -> p.getBlock.getType)))
    case r: RemoveBlock =>
      db.tell(DbActor.RemoveBlock(r.getPosition, sender), blockManager)
      blockUpdateEvents.foreach(e => e ! new EventBlockRemoved(Set(r.getPosition)))
    case v: ViewBlock =>
      db.tell(DbActor.ViewBlock(v.getPosition, sender), blockManager)
    case r: ReplaceBlocks =>
      blockUpdateEvents.foreach(e =>
        e ! new EventBlockUpdated(r.getBlocks)
      )
      db forward r
    case c: CreateInventory =>
      inventoryManager.forward(c)
    case g: GetInventory =>
      inventoryManager.forward(g)
    case p: PutStack =>
      inventoryManager.forward(p)
    case r: RemoveStack =>
      inventoryManager.forward(r)
    case g: GetStack =>
      inventoryManager.forward(g)
    case d: DeleteInventory =>
      inventoryManager.forward(d)
    case m: MatchPattern =>
      konstructing.forward(m)
    case k: KonstructPattern =>
      konstructing.forward(k)
    case q: BoxQuery =>
      db forward q
    case GetBlockFactory =>
      blockManager.forward(GetBlockFactory)
    case GetTextures =>
      blockManager.forward(GetTextures)
  }
}

object UniverseActor {
  case class CreatePlayer(nick: String, password: String)

  import konstructs.plugin.Plugin.nullAsEmpty

  @PluginConstructor
  def props(
             name: String,
             notUsed: ActorRef,

             @Config(key = "binary-storage") binaryStorage: ActorRef,
             @Config(key = "json-storage") jsonStorage: ActorRef,
             @Config(key = "inventory-manager") inventoryManager: ActorRef,
             @Config(key = "konstructing") konstructing: ActorRef,
             @Config(key = "block-manager") blockManager: ActorRef,

             @ListConfig(
               key = "chat-filters",
               elementType = classOf[ActorRef],
               optional = true
             ) chatFilters: Seq[ActorRef],


             @ListConfig(
               key = "block-update-events",
               elementType = classOf[ActorRef],
               optional = true
             ) blockUpdateEvents: Seq[ActorRef],

             @ListConfig(
               key = "primary-interaction-listeners",
               elementType = classOf[ActorRef],
               optional = true
             ) primaryListeners: Seq[ActorRef],

             @ListConfig(
               key = "secondary-interaction-listeners",
               elementType = classOf[ActorRef],
               optional = true
             ) secondaryListeners: Seq[ActorRef],

             @ListConfig(
               key = "tertiary-interaction-listeners",
               elementType = classOf[ActorRef],
               optional = true
             ) tertiaryListeners: Seq[ActorRef]

           ): Props = Props(
                             classOf[UniverseActor],
                             name,
                             jsonStorage,
                             binaryStorage,
                             inventoryManager,
                             konstructing,
                             blockManager,
                             nullAsEmpty(chatFilters),
                             nullAsEmpty(blockUpdateEvents),
                             nullAsEmpty(primaryListeners),
                             nullAsEmpty(secondaryListeners),
                             nullAsEmpty(tertiaryListeners)
                           )
}
