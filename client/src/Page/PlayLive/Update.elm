module Page.PlayLive.Update exposing (..)

import Time exposing (millisecond, second)
import Time exposing (Time)
import Result exposing (Result(Ok, Err))
import Response exposing (..)
import Model.Shared exposing (..)
import Page.PlayLive.Model as Model exposing (..)
import Page.PlayLive.Decoders as Decoders
import ServerApi
import Game.Shared exposing (defaultGame, GameState)
import Game.Update as Game
import Game.Outputs as Output
import Task
import WebSocket
import Http


subscriptions : String -> Model -> Sub Msg
subscriptions host model =
    case model.liveTrack of
        DataOk liveTrack ->
            Sub.batch
                [ WebSocket.listen
                    (ServerApi.gameSocket host liveTrack.track.id)
                    Decoders.decodeStringMsg
                , Sub.map GameMsg (Game.subscriptions model)
                ]

        _ ->
            Sub.none


mount : String -> Response Model Msg
mount id =
    Cmd.batch [ load id, Cmd.map GameMsg Game.mount ]
        |> res initial


update : Player -> String -> Msg -> Model -> Response Model Msg
update player host msg model =
    case msg of
        Load result ->
            case result of
                Ok ( liveTrack, course ) ->
                    Task.perform (InitGameState liveTrack course) Time.now
                        |> res model

                Err e ->
                    res { model | liveTrack = DataErr e } Cmd.none

        InitGameState liveTrack course time ->
            let
                gameState =
                    defaultGame time course player

                newModel =
                    { model | gameState = Just gameState }
                        |> applyLiveTrack liveTrack
            in
                res newModel Cmd.none

        GameMsg gameMsg ->
            Game.update player (Output.sendToTrackServer host model.liveTrack) gameMsg model
                |> mapCmd GameMsg

        SetTab tab ->
            res { model | tab = tab } Cmd.none

        UpdateLiveTrack liveTrack ->
            res (applyLiveTrack liveTrack model) Cmd.none

        Model.NoOp ->
            res model Cmd.none


applyLiveTrack : LiveTrack -> Model -> Model
applyLiveTrack ({ track, players, races } as liveTrack) model =
    let
        racePlayers =
            List.concatMap .players races

        inRace p =
            List.member p racePlayers

        freePlayers =
            List.filter (not << inRace) players
    in
        { model | liveTrack = DataOk liveTrack, races = races, freePlayers = freePlayers }


load : String -> Cmd Msg
load id =
    Task.map2 (,) (Http.toTask (ServerApi.getLiveTrack id)) (Http.toTask (ServerApi.getCourse id))
        |> Task.attempt Load
