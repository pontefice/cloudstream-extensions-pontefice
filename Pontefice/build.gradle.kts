// use an integer for version numbers
version = 1


cloudstream {
    language = "it"
    // All of these properties are optional, you can safely remove them

    // description = "Lorem Ipsum"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "TvSeries",
        "Movie",
    )

    iconUrl = "https://static.independent.co.uk/s3fs-public/thumbnails/image/2017/05/17/09/the-young-pope.jpg?quality=75&width=990&crop=1214%3A809%2Csmart&auto=webp"
}
