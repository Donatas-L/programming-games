import cats.data.Validated
import cats.data.Validated._
import cats.implicits._

import scala.collection.SortedSet

class Suite private (val suite : Char) extends Ordered[Suite] {
  override def compare(that: Suite): Int = {
    val indexedSeq = Suite.validOrderedSuites.toIndexedSeq
    indexedSeq.indexOf(suite) - indexedSeq.indexOf(that.suite)
  }
  override def equals(o: Any): Boolean = o match {
    case that: Suite => compare(that) == 0
    case _ => false
  }
  override def toString: String = suite match {
    case 'H' => "♥"
    case 'D' => "♦"
    case 'C' => "♣"
    case 'S' => "♠"
    case _ => "???"
  }

  override def hashCode(): Int = suite
}
object Suite {
  val validOrderedSuites: Array[Char] = Array(
    'H', // Hearths
    'D', // Diamonds
    'C', // Clubs
    'S'  // Spades
  )

  def apply(suite: Char): Validated[ErrorMsg, Suite] = {
    if (validOrderedSuites.contains(suite)) Valid(new Suite(suite))
    else Invalid(ErrorMsg(s"$suite is not valid field for ${Suite.getClass.getName}"))
  }

  def unsafeApply(suite: Char): Suite = new Suite(suite)
}

class Rank private (val rank : Char) extends Ordered[Rank] {
  override def compare(that: Rank): Int = {
    val indexedSeq = Rank.validOrderedRanks.toIndexedSeq
    indexedSeq.indexOf(rank) - indexedSeq.indexOf(that.rank)
  }

  override def equals(o: Any): Boolean = o match {
    case that: Rank => compare(that) == 0
    case _ => false
  }

  override def toString: String = rank.toString

  override def hashCode(): Int = rank
}
object Rank {
  val validOrderedRanks: Array[Char] = Array(
    '2', '3', '4', '5', '6', '7', '8', '9',
    'T', // 10
    'J', // Jack
    'Q', // Queen,
    'K', // King,
    'A'  // Ace
  )

  def apply(rank : Char) : Validated[ErrorMsg, Rank] = {
    if (validOrderedRanks.contains(rank)) Valid(new Rank(rank))
    else Invalid(ErrorMsg(s"$rank is not valid field for ${Rank.getClass.getName}"))
  }

  def unsafeApply(rank: Char): Rank = new Rank(rank)
}

case class Card(suite: Suite, rank: Rank) extends Ordered[Card] {
  override def compare(that: Card): Int = {
    val rank = this.rank.compare(that.rank)
    if (rank == 0) this.suite.compare(that.suite) else rank
  }

  override def toString: String = s"$suite$rank"
}
object Card {
  def parse(s: String): Validated[ErrorMsg, Card] = {
    if (s.length != 2) Invalid(ErrorMsg(s"Incorrect number of symbols in $s"))
    else {
      val suitePart = s.charAt(0)
      val rankPart = s.charAt(1)

      Suite(suitePart).andThen(suite => Rank(rankPart).map(rank => Card(suite, rank)))
    }
  }
}

case class Cards private(value: SortedSet[Card]) {
  def cardToAttack : Option[Card] = this.value.headOption
  def cardToReflect(rankToReflect: Rank): Option[Card] = this.value.find(card => card.rank == rankToReflect)

  def withCards(fn : SortedSet[Card] => SortedSet[Card]): Cards = this.copy(value = fn(value))
  def removeCards(cardsToRemove: scala.collection.Set[Card]): Cards = withCards(cards => value.diff(cardsToRemove))
}
object Cards {
  def apply(cards: Set[Card])(implicit trumpSuite: Suite): Cards = {
    implicit val orderByTrumpSuite: Ordering[Card] =
      Ordering
        .by[Card, Boolean](card => card.suite == trumpSuite)
        .orElse(Ordering.by[Card, Rank](c => c.rank))
        .orElse(Ordering.by[Card, Suite](c => c.suite))

    new Cards(SortedSet.empty[Card] ++ cards)
  }

  def parse(s: String, separator: Char = ' ')(implicit trumpSuite: Suite): Validated[ErrorMsg, Cards] =
    s.trim.split(separator)
      .foldLeft(
        Valid(Set.empty[Card]): Validated[ErrorMsg, Set[Card]]
      )(
        (cards, s) => Card.parse(s).andThen(c => cards.map(cc => cc + c))
      ).map(cards => Cards(cards))
}

case class CardPair(placed: Card, covering: Option[Card]) {
  override def toString: String = s"$placed/${covering.fold(ifEmpty = "-")(c => c.toString)}"
}
case class Player(id: Int, cards: Cards) {
  def withCards(fn: Cards => Cards): Player = this.copy(cards = fn(cards))

  override def toString: String = s"P$id[$cards]"
}
object Players {
  def parse(s: String, separatorForPlayers: Char = '|')(implicit trumpSuite: Suite)
  : Validated[ErrorMsg, (Player, Player)] = {
    val playersCards = s.trim.split(separatorForPlayers)
    if (playersCards.length != 2) Invalid(ErrorMsg(s"Couldn't split $s into two pieces"))
    else {
      val firstPlayer = Cards.parse(playersCards.head).map(cards => Player(1, cards))
      val secondPlayer = Cards.parse(playersCards.last).map(cards => Player(2, cards))

      firstPlayer.andThen(first => secondPlayer.map(second => (first, second)))
    }
  }
}

case class GameState(offense: Player, defense: Player, cardsOnTable: Set[CardPair])(implicit trumpSuite: Suite) {
  override def toString: String =
  s"""State[
    trump: $trumpSuite
     o: $offense
     d: $defense
     t: $cardsOnTable
  ]"""

  private def withCardsOnTable(fn: Set[CardPair] => Set[CardPair]) = this.copy(cardsOnTable = fn(cardsOnTable))
  private def withOffense(fn: Player => Player) = this.copy(offense = fn(offense))
  private def withDefense(fn: Player => Player) = this.copy(defense = fn(defense))

  private def addCardsToTable(cardsToAdd: Iterable[Card]) =
    withCardsOnTable(onTable => onTable ++ cardsToAdd.map(c => CardPair(c, None)))

  private def getCardsToDefeat: Option[Cards] = {
    val cards = Cards(this.cardsOnTable.filter(pair => pair.covering.isEmpty).map(pair => pair.placed))
    if (cards.value.isEmpty) None else cards.some
  }

  private def canTryToReflect(rankToCheckForReflect: Rank) = {
    getCardsToDefeat match {
      case Some(cards) =>
        cards.value.forall(card => card.rank == rankToCheckForReflect) && this.offense.cards.value.size > cards.value.size
      case None => false
    }
  }

  private def getAllCardsOnTable =
    cardsOnTable.flatMap(pair => pair.covering.map(c => c).toList :+ pair.placed)

  def swapRoles: GameState =
    this
      .withOffense(_ => defense)
      .withDefense(_ => offense)

  def noCardsLeftToPlay: Boolean = cardsOnTable.isEmpty && (offense.cards.value.isEmpty || defense.cards.value.isEmpty)

  def tryGetReflectCard(rankToCheckForReflect: Rank): Option[Card] = {
    if (canTryToReflect(rankToCheckForReflect)) this.defense.cards.cardToReflect(rankToCheckForReflect)
    else None
  }

  def getCardToDefeat: Option[Card] = this.getCardsToDefeat.map(c => c.value.head)

  def cardToDefend(cardNeededToDefeat: Card): Option[Card] =
    this.defense.cards.value.find(card => {
      if (cardNeededToDefeat.suite == trumpSuite) card.suite == trumpSuite && card > cardNeededToDefeat
      else card.suite == cardNeededToDefeat.suite && card > cardNeededToDefeat || card.suite == trumpSuite
    })

  def determineWinner: Option[Player] = {
    val offenseCards = offense.cards.value.size
    val defenseCards = defense.cards.value.size
    if (offenseCards < defenseCards) offense.some else if (offenseCards == defenseCards) None else defense.some
  }

  def attack(pickedCardForAttack: Card): GameState =
    this
      .withOffense(offense => offense.withCards(c => c.removeCards(Set(pickedCardForAttack))))
      .addCardsToTable(Set.empty + pickedCardForAttack)

  def reflect(cardUsedToReflect: Card): GameState =
    this
      .withDefense(defense => defense.withCards(c => c.removeCards(Set(cardUsedToReflect))))
      .addCardsToTable(Set.empty + cardUsedToReflect)
      .swapRoles

  def defend(cardToDefeat: Card, defendingCard: Card): Validated[ErrorMsg, GameState] =
    cardsOnTable
      .find(pair => pair.placed == cardToDefeat)
      .map(pair => cardsOnTable - pair + CardPair(pair.placed, defendingCard.some))
      .fold[Validated[ErrorMsg, GameState]](
        ifEmpty = Invalid(ErrorMsg(s"$cardToDefeat wasn't found on table"))
      )(newCardsOnTable =>
        Valid(withCardsOnTable(_ => newCardsOnTable).withDefense(d => d.withCards(c => c.removeCards(Set(defendingCard)))))
      )

  def reinforce(extraCards: SortedSet[Card]): GameState =
    this
      .withOffense(offense => offense.withCards(c => c.removeCards(extraCards)))
      .addCardsToTable(extraCards)

  def offenseReinforce: Option[SortedSet[Card]] = {
    val cardsCanBeAdded = defense.cards.value.size - getCardsToDefeat.fold(ifEmpty = 0)(c => c.value.size)
    Option.when(cardsCanBeAdded > 0)(
      offense.cards.value
      .filter(card => getAllCardsOnTable.map(c => c.rank).contains(card.rank)      )
      .take(cardsCanBeAdded)
    ).flatMap(cards => if (cards.isEmpty) None else cards.some)
  }

  def removeCardsFromTable: GameState = this.withCardsOnTable(_ => Set.empty)

  def defensePicksCardsFromTable: GameState =
    this
      .withDefense(d => d.withCards(cards => cards.withCards(c => c ++ getAllCardsOnTable)))
      .withCardsOnTable(_ => Set.empty)
}

case class ErrorMsg(error: String)
case class GameStateLog(value: String)

object Main {
  def main(args: Array[String]): Unit = {
    println(runGame("test1.txt")(GameStateLog("").some))
  }

  def runGame(gameResourceFilename: String)(implicit log: Option[GameStateLog]): String =
    parseResource(readResource(gameResourceFilename))
    .map(states => states.map(s => play(s)(log)))
    .fold(
      e => s"!!! ERRORS WHILE PARSING $gameResourceFilename !!! \n$e",
      statesV => statesV.map(v => v.fold(
        e => s"!!! ERRORS WHILE PROCESSING GAME STATE !!! \n$e",
        tpl => {
          val state = tpl._1
          val logOpt = tpl._2
          logOpt.fold(ifEmpty = "")(log => log.value) + state.determineWinner.fold(ifEmpty = "0")(p => p.id.toString)
        }
      )).reduceLeft(_ + _)
    )

  def parseResource(r: Validated[ErrorMsg, (Char, List[String])]): Validated[ErrorMsg, List[GameState]] =
    r.andThen(tpl => {
      val trumpSuitePart = tpl._1
      val playersPart = tpl._2
      Suite(trumpSuitePart).andThen(implicit trumpSuite =>
        playersPart.foldLeft(
          Valid(List.empty[GameState]): Validated[ErrorMsg, List[GameState]]
        )(
          (list, s) => Players.parse(s).andThen(tpl => list.map(state => state :+ GameState(tpl._1, tpl._2, Set.empty)))
        )
      )
    })

  def readResource(filename: String): Validated[ErrorMsg, (Char, List[String])] = {
    val source = scala.io.Source.fromResource(filename)
    val l = source.getLines.toList.map(s => s.trim)
    source.close
    if (l.size < 2) Invalid(ErrorMsg(s"File ($filename) should contain at least 2 lines"))
    else Valid((l.head.head, l.tail))
  }

  def play(state: GameState)(log: Option[GameStateLog])(implicit turn : Int = 1) : Validated[ErrorMsg, (GameState, Option[GameStateLog])] = {
    def addToLog(s: String) = log.map(l => GameStateLog(l.value + s"$s: $state\n"))
    if (state.noCardsLeftToPlay) return Valid((state, addToLog("End of the game")))

    state.cardsOnTable.headOption match {
      case Some(firstCardPair) =>
        val rankToCheckForReflect = firstCardPair.placed.rank
        state.tryGetReflectCard(rankToCheckForReflect) match {
          case Some(value) => play(state.reflect(value))(addToLog(s"Reflecting with $value"))
          case None =>
            state.getCardToDefeat match {
              case Some(cardToDefeat) =>
                state.cardToDefend(cardToDefeat) match {
                  case Some(defendingCard) => state
                  .defend(cardToDefeat, defendingCard)
                  .andThen(state => play(state)(addToLog(s"Needed to defeat $cardToDefeat defending with $defendingCard")))
                  case None => state.offenseReinforce match {
                    case Some(value) => play(state.reinforce(value))(
                      addToLog(s"Defense couldn't defend & offense reinforce with $value")
                    )
                    case None => play(state.defensePicksCardsFromTable)(addToLog(s"Couldn't defend against $cardToDefeat"))
                  }
                }
              case None => state.offenseReinforce match {
                case Some(value) => play(state.reinforce(value))(addToLog(s"Offense reinforce with $value"))
                case None => play(state.removeCardsFromTable.swapRoles)(addToLog("Turn ended"))
              }
            }
        }
      case None => state.offense.cards.cardToAttack match {
        case Some(value) => {

          play(state.attack(value))(addToLog(s"Turn $turn \nAttacking with $value"))(turn + 1)
        }
        case None => Valid(state, addToLog("There are no cards to attack"))
      }
    }
  }
}