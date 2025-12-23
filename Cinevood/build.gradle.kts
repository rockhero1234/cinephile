// use an integer for version numbers
version = 11
cloudstream {
    description ="unstable right now but good regional content"
    authors = listOf("Dilip")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    // List of video source types. Users are able to filter for extensions in a given category.
    // You can find a list of available types here:
    // https://recloudstream.github.io/cloudstream/html/app/com.lagradost.cloudstream3/-tv-type/index.html
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
    language = "hi"

    iconUrl = "https://i.ibb.co/nMdxZgkR/8-CM3-5q-ARZYELDWg6-Erqfg-NYpo-Sdh-Yw-HRB5-CMhf-Vgg-Ygn-PMHCVYWQf-EEJMt9gwd6-EFP5t-LYgd-LAm-Zerm-KCX.png"
}
