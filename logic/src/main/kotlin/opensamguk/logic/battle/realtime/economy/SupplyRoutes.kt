package opensamguk.logic.battle.realtime.economy

enum class RoutePhase { LOADING, OUTBOUND, RETURNING, RECALLING, STOPPED }
enum class RouteCommand { STOP, RECALL, CHANGE_AMOUNT, PAUSE, REMOVE }
data class SupplyRoute(
    val id: String, val owner: String, val carrierId: String, val from: String, val to: String,
    val amount: ResourceBundle?, val legTicks: Long?, val phase: RoutePhase = RoutePhase.LOADING,
    val remainingTicks: Long = 0, val trips: Long = 0, val stoppedFrom: RoutePhase? = null,
)
fun EconomyLedger.createRoute(id: String, owner: String, routeId: String, carrierId: String, from: String, to: String, mode: EconomyMode, params: EconomyParams) =
    apply(id, key("route", owner, routeId, carrierId, from, to, mode, params.fingerprint())) { s ->
        identifier(routeId); ensure(from != to, EconomyCode.INVALID_INPUT)
        val turns = params.turns(mode, params.routeTurnCost)
        val carrier = s.carrier(carrierId, owner); ensure(carrier.kind == CarrierKind.TRANSPORT, EconomyCode.CARRIER_NOT_ALLOWED)
        ensure(carrier.cargo == ResourceBundle.ZERO, EconomyCode.ROUTE_BUSY); s.freeCarrier(carrierId)
        s.store(from, owner); s.store(to, owner); ensure(routeId !in s.routes, EconomyCode.DUPLICATE_ID)
        val ready = params.status == ParameterStatus.CONFIRMED
        Change(s.copy(routes = s.routes + (routeId to SupplyRoute(routeId, owner, carrierId, from, to,
            if (ready) params.routeAmount else null, if (ready) params.routeLegTicks else null))), turns)
    }
fun EconomyLedger.changeRoute(id: String, owner: String, routeId: String, to: String, mode: EconomyMode, params: EconomyParams) =
    apply(id, key("changeRoute", owner, routeId, to, mode, params.fingerprint())) { s ->
        val route = s.routes.required(routeId); ensure(route.owner == owner, EconomyCode.NOT_OWNER)
        ensure(route.phase == RoutePhase.LOADING, EconomyCode.ROUTE_BUSY)
        val turns = params.turns(mode, params.routeTurnCost); s.store(to, owner)
        ensure(route.from != to, EconomyCode.INVALID_INPUT)
        params.confirmed()
        Change(s.copy(routes = s.routes + (routeId to route.copy(to = to, amount = params.routeAmount, legTicks = params.routeLegTicks))), turns)
    }
/** Clock/graph integration is separate. A tick consumes no turn in either world mode. */
fun EconomyLedger.advanceRoute(id: String, routeId: String, connected: Boolean?, params: EconomyParams) =
    apply(id, key("routeTick", routeId, connected, params.fingerprint())) { s ->
        val route = s.routes.required(routeId); val carrier = s.carrier(route.carrierId, route.owner)
        if (route.phase == RoutePhase.STOPPED) throw Rejected(EconomyCode.ROUTE_STOPPED)
        // A direct C50 recall is explicit; automatic handling of a cut route remains undecided.
        if (route.phase != RoutePhase.RECALLING) ensure(connected == true, EconomyCode.NOT_DECIDED)
        s.store(route.from, route.owner); s.store(route.to, route.owner)
        if (route.phase == RoutePhase.LOADING) {
            params.confirmed()
            ensure(route.amount != null && route.legTicks != null, EconomyCode.NOT_DECIDED)
            val amount = route.amount!!; val ticks = route.legTicks!!
            ensure(amount.total > 0 && ticks > 0, EconomyCode.INVALID_INPUT)
            val capacity = params.positive(params.carrierCapacity); ensure(amount.total <= capacity, EconomyCode.CAPACITY_EXCEEDED)
            val from = s.store(route.from, route.owner)
            val balance = from.stock - amount // Insufficient stock waits without a partial load.
            return@apply Change(s.copy(stores = s.stores + (from.id to from.copy(stock = balance)),
                carriers = s.carriers + (carrier.id to carrier.copy(cargo = amount)),
                routes = s.routes + (routeId to route.copy(phase = RoutePhase.OUTBOUND, remainingTicks = ticks))))
        }
        val remaining = (route.remainingTicks - 1).coerceAtLeast(0)
        if (remaining > 0) return@apply Change(s.copy(routes = s.routes + (routeId to route.copy(remainingTicks = remaining))))
        when (route.phase) {
            RoutePhase.OUTBOUND -> {
                val to = s.store(route.to, route.owner)
                Change(s.copy(stores = s.stores + (to.id to to.copy(stock = to.stock + carrier.cargo)),
                    carriers = s.carriers + (carrier.id to carrier.copy(cargo = ResourceBundle.ZERO)),
                    routes = s.routes + (routeId to route.copy(phase = RoutePhase.RETURNING, remainingTicks = route.legTicks!!, trips = Math.incrementExact(route.trips)))))
            }
            RoutePhase.RETURNING -> Change(s.copy(routes = s.routes + (routeId to route.copy(phase = RoutePhase.LOADING, remainingTicks = 0))))
            RoutePhase.RECALLING -> {
                val from = s.store(route.from, route.owner)
                Change(s.copy(stores = s.stores + (from.id to from.copy(stock = from.stock + carrier.cargo)),
                    carriers = s.carriers + (carrier.id to carrier.copy(cargo = ResourceBundle.ZERO)),
                    routes = s.routes + (routeId to route.copy(phase = RoutePhase.STOPPED, remainingTicks = 0))))
            }
            else -> throw Rejected(EconomyCode.INVALID_INPUT)
        }
    }
fun EconomyLedger.commandRoute(id: String, owner: String, routeId: String, command: RouteCommand, mode: EconomyMode) =
    apply(id, key("routeCommand", owner, routeId, command, mode)) { s ->
        val route = s.routes.required(routeId); ensure(route.owner == owner, EconomyCode.NOT_OWNER)
        s.carrier(route.carrierId, owner)
        val next = when (command) {
            RouteCommand.STOP -> if (route.phase == RoutePhase.STOPPED) route else route.copy(phase = RoutePhase.STOPPED, stoppedFrom = route.phase)
            RouteCommand.RECALL -> {
                val phase = if (route.phase == RoutePhase.STOPPED) route.stoppedFrom else route.phase
                val ticks = when (phase) {
                    RoutePhase.LOADING -> 0
                    RoutePhase.OUTBOUND -> route.legTicks!! - route.remainingTicks
                    RoutePhase.RETURNING, RoutePhase.RECALLING -> route.remainingTicks
                    else -> throw Rejected(EconomyCode.NOT_DECIDED)
                }
                route.copy(phase = RoutePhase.RECALLING, remainingTicks = ticks)
            }
            else -> throw Rejected(EconomyCode.NOT_DECIDED)
        }
        Change(s.copy(routes = s.routes + (routeId to next)))
    }
