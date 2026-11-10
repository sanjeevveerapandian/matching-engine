package com.sanjeev.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Place, cancel and modify orders. A controller only translates: it checks the request, converts
 * rupees to paise, calls the exchange service, and converts the answer back. The rules of trading
 * live in the engine, not here.
 */
@RestController
@RequestMapping("/api/orders")
class OrderController {

    private final ExchangeService exchange;

    OrderController(ExchangeService exchange) {
        this.exchange = exchange;
    }

    /** 201 with what happened to the order (resting, filled, partly filled, cancelled) and its trades. */
    @PostMapping
    ResponseEntity<Responses.PlaceOrder> place(@Valid @RequestBody PlaceOrderRequest request) {
        Domain.PlacedOrder placed = exchange.placeOrder(request.toSpec());
        return ResponseEntity.status(HttpStatus.CREATED).body(Responses.PlaceOrder.from(placed));
    }

    /** 200 with the quantity that was cancelled; 404 if no such order is resting. */
    @DeleteMapping("/{id}")
    Responses.CancelOrder cancel(@PathVariable("id") long id) {
        requireValidId(id);
        return Responses.CancelOrder.from(exchange.cancelOrder(id));
    }

    /** 200 with whether the order kept its place and any trades it caused; 404 if no such order is resting. */
    @PatchMapping("/{id}")
    Responses.ModifyOrder modify(@PathVariable("id") long id, @Valid @RequestBody ModifyOrderRequest request) {
        requireValidId(id);
        return Responses.ModifyOrder.from(exchange.modifyOrder(id, request.newPricePaise(), request.quantity()));
    }

    /** Ids start at 1; anything else cannot exist, so say so clearly instead of answering "not found". */
    private static void requireValidId(long id) {
        if (id < 1) {
            throw new InvalidRequestException("id", "order id must be a positive whole number");
        }
    }
}
